package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.Latches.awaitQuietly;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.testsupport.PosixPermissions;

class OwnerOnlyFilesTest {

    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @TempDir
    Path directory;

    @BeforeEach
    void requiresPosixPermissions() {
        PosixPermissions.assumeSupported();
    }

    @Test
    void createsEveryMissingDirectoryReadableByTheOwnerAlone() throws IOException {
        Path nested = directory.resolve("outer").resolve("inner");

        OwnerOnlyFiles.createDirectories(nested);

        assertThat(PosixPermissions.of(nested)).isEqualTo("rwx------");
        assertThat(PosixPermissions.of(nested.getParent())).isEqualTo("rwx------");
    }

    @Test
    void createsAFileReadableByTheOwnerAlone() throws IOException {
        Path file = directory.resolve("state.tmp");

        write(file, "content");

        assertThat(PosixPermissions.of(file)).isEqualTo("rw-------");
        assertThat(Files.readString(file)).isEqualTo("content");
    }

    @Test
    void replacesALeftoverFileAtThePath() throws IOException {
        Path file = directory.resolve("state.tmp");
        write(file, "first");

        write(file, "second");

        assertThat(Files.readString(file)).isEqualTo("second");
    }

    /** A symlink planted at the temporary path must not turn a state write into a write somewhere else. */
    @Test
    void neverWritesThroughASymlinkPlantedAtThePath() throws IOException {
        Path victim = directory.resolve("victim");
        Files.writeString(victim, "untouched");
        Path file = directory.resolve("state.tmp");
        Files.createSymbolicLink(file, victim);

        write(file, "content");

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(file)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("content");
    }

    @Test
    void replacesTheFileInOneStepAndLeavesNoTemporaryBehind() throws IOException {
        Path file = directory.resolve("nested").resolve("state");
        replace(file, "first");

        replace(file, "second");

        assertThat(Files.readString(file)).isEqualTo("second");
        assertThat(PosixPermissions.of(file)).isEqualTo("rw-------");
        assertThat(namesIn(directory.resolve("nested"))).containsExactly("state");
    }

    /** A rewrite that fails halfway must cost nothing: the previous file stays, and no temporary lingers. */
    @Test
    void aFailedRewriteKeepsThePreviousFileAndLeavesNoTemporaryBehind() throws IOException {
        Path file = directory.resolve("state");
        replace(file, "first");
        IOException diskFull = new IOException("disk full");

        assertThatThrownBy(() -> OwnerOnlyFiles.replaceAtomically(file, out -> {
                    out.write('x');
                    throw diskFull;
                }))
                .isSameAs(diskFull);

        assertThat(Files.readString(file)).isEqualTo("first");
        assertThat(namesIn(directory)).containsExactly("state");
    }

    /** A store that stops interrupts its writer thread, and a write caught in flight must end there. */
    @Test
    void anInterruptEndsAWriteInFlightAndKeepsThePreviousFile() throws Exception {
        Path file = directory.resolve("state");
        replace(file, "first");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread interrupted = writer(file, failure, out -> {
            Thread.currentThread().interrupt();
            out.write(bytes("second"));
        });

        interrupted.start();
        interrupted.join();

        assertThat(failure.get()).isInstanceOf(ClosedByInterruptException.class);
        assertThat(file).hasContent("first");
        assertThat(namesIn(directory)).containsExactly("state");
    }

    /** The two threads stand in for two JVMs of one application that share a storage directory. */
    @Test
    void twoWritersOfOneFileNeverMoveEachOthersHalfWrittenTemporaryIntoPlace() throws Exception {
        Path file = directory.resolve("state");
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch secondHalfWritten = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = writer(file, firstFailure, out -> {
            out.write(bytes("first-complete"));
            out.flush();
            firstWritten.countDown();
            awaitQuietly(secondHalfWritten);
        });
        Thread second = writer(file, secondFailure, out -> {
            out.write(bytes("second-"));
            out.flush();
            secondHalfWritten.countDown();
            awaitQuietly(firstDone);
            out.write(bytes("complete"));
        });
        first.start();
        awaitQuietly(firstWritten);
        second.start();
        first.join();

        try {
            assertThat(file).hasContent("first-complete");
        } finally {
            firstDone.countDown();
            second.join();
        }
        assertThat(file).hasContent("second-complete");
        assertThat(namesIn(directory)).containsExactly("state");
        assertThat(firstFailure).hasNullValue();
        assertThat(secondFailure).hasNullValue();
    }

    /** A writer that died mid-write never removes its temporary, and no later write reuses the name. */
    @Test
    void aTemporaryLeftBehindLongAgoIsRemovedByTheNextWrite() throws IOException {
        Path leftBehind = temporary("state.abc.tmp", LONG_AGO);

        replace(directory.resolve("state"), "content");

        assertThat(leftBehind).doesNotExist();
    }

    @Test
    void anotherWritersFreshTemporaryIsLeftAlone() throws IOException {
        Path inProgress = Files.writeString(directory.resolve("state.abc.tmp"), "half");

        replace(directory.resolve("state"), "content");

        assertThat(inProgress).hasContent("half");
    }

    @Test
    void aFileThatOnlyLooksLikeATemporaryOfAnotherFileIsLeftAlone() throws IOException {
        Path unrelated = temporary("other.abc.tmp", LONG_AGO);

        replace(directory.resolve("state"), "content");

        assertThat(unrelated).hasContent("half");
    }

    /** {@code state.tmp} is a name a caller may write through {@link OwnerOnlyFiles#newOutputStream} itself. */
    @Test
    void aFileWithNothingBetweenTheNameAndTheSuffixIsLeftAlone() throws IOException {
        Path unrelated = temporary("state.tmp", LONG_AGO);

        replace(directory.resolve("state"), "content");

        assertThat(unrelated).hasContent("half");
    }

    @Test
    void aTemporaryCountsAsLeftBehindOnceItIsOverAnHourOlderThanTheWrite() throws IOException {
        Path state = directory.resolve("state");
        replace(state, "first");
        Instant written = Files.getLastModifiedTime(state).toInstant();
        Path overAnHour = temporary("state.old.tmp", written.minus(Duration.ofMinutes(61)));
        Path underAnHour = temporary("state.young.tmp", written.minus(Duration.ofMinutes(59)));

        replace(state, "second");

        assertThat(overAnHour).doesNotExist();
        assertThat(underAnHour).hasContent("half");
    }

    @Test
    void aLockFileIsCreatedWithItsDirectoryReadableByTheOwnerAlone() throws IOException {
        Path file = directory.resolve("storage").resolve(".lock");

        OwnerOnlyFiles.openLockFile(file).close();

        assertThat(PosixPermissions.of(file)).isEqualTo("rw-------");
        assertThat(PosixPermissions.of(file.getParent())).isEqualTo("rwx------");
    }

    /** A lock is held on the file itself, so a lock file replaced by a new one would lock nothing. */
    @Test
    void openingALockFileKeepsTheFileThatIsThere() throws IOException {
        Path file = directory.resolve(".lock");
        Files.writeString(file, "held");

        OwnerOnlyFiles.openLockFile(file).close();

        assertThat(Files.readString(file)).isEqualTo("held");
    }

    @Test
    void aSymlinkPlantedAtTheLockPathIsNotFollowed() throws IOException {
        Path victim = directory.resolve("victim");
        Path file = directory.resolve(".lock");
        Files.createSymbolicLink(file, victim);

        assertThatThrownBy(() -> OwnerOnlyFiles.openLockFile(file)).isInstanceOf(IOException.class);

        assertThat(victim).doesNotExist();
    }

    private Path temporary(String name, Instant lastModified) throws IOException {
        Path temporary = Files.writeString(directory.resolve(name), "half");
        return Files.setLastModifiedTime(temporary, FileTime.from(lastModified));
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }

    private static Thread writer(Path file, AtomicReference<Throwable> failure, OwnerOnlyFiles.Content content) {
        return Thread.ofPlatform().unstarted(() -> {
            try {
                OwnerOnlyFiles.replaceAtomically(file, content);
            } catch (IOException | RuntimeException | AssertionError e) {
                failure.set(e);
            }
        });
    }

    private static void replace(Path file, String content) throws IOException {
        OwnerOnlyFiles.replaceAtomically(file, out -> out.write(bytes(content)));
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) throws IOException {
        try (OutputStream out = OwnerOnlyFiles.newOutputStream(file)) {
            out.write(bytes(content));
        }
    }
}
