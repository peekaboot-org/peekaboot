package org.peekaboot.backend.tracing.digest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.testsupport.LogCapture;

class ErrorDigestFileTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    private static final DigestedError OUT_OF_STOCK = new DigestedError(
            "java.lang.IllegalStateException",
            "/orders/{id}",
            "org.acme.Shop.buy(Shop.java)",
            3,
            T0,
            T0.plusSeconds(5),
            "out of stock",
            "t3",
            List.of(T0.minusSeconds(60)));

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(ErrorDigestFile.FILE_NAME);
    }

    private static List<String> messages(LogCapture capture) {
        return capture.appender().list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void whatOneRunWritesTheNextRestores() {
        ErrorDigest first = new ErrorDigest();
        first.restore(List.of(OUT_OF_STOCK));
        first.record(FailedSummaries.failed("t4").startedAt(T0.plusSeconds(9)).build());
        new ErrorDigestFile(dir, first).flush();

        ErrorDigest second = new ErrorDigest();
        new ErrorDigestFile(dir, second).restore();

        assertThat(second.entries()).isEqualTo(first.entries());
    }

    @Test
    void theFileStartsWithItsVersionAndHoldsOneEntryALine() throws IOException {
        ErrorDigest digest = new ErrorDigest();
        digest.record(FailedSummaries.failed("t1").build());

        new ErrorDigestFile(dir, digest).flush();

        List<String> lines = Files.readAllLines(file());
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).isEqualTo("{\"version\":1}");
        assertThat(lines.get(1)).contains("\"lastTraceId\":\"t1\"");
    }

    /** The writer flushes every second; a digest nothing was recorded in since the last write is not written again. */
    @Test
    void anUnchangedDigestIsNotWrittenAgain() throws IOException {
        ErrorDigest digest = new ErrorDigest();
        digest.record(FailedSummaries.failed("t1").build());
        ErrorDigestFile digestFile = new ErrorDigestFile(dir, digest);
        digestFile.flush();
        Files.writeString(file(), "{\"version\":1}\n");

        digestFile.flush();

        assertThat(Files.readAllLines(file())).containsExactly("{\"version\":1}");
    }

    @Test
    void aMissingFileRestoresNothingAndSaysNothing() {
        ErrorDigest digest = new ErrorDigest();
        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            new ErrorDigestFile(dir, digest).restore();

            assertThat(capture.appender().list).isEmpty();
        }
        assertThat(digest.entries()).isEmpty();
    }

    @Test
    void aFileOfAnotherVersionIsDeletedWithOneLine() throws IOException {
        Files.writeString(file(), "{\"version\":99}\n{\"count\":1}\n");
        ErrorDigest digest = new ErrorDigest();

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            new ErrorDigestFile(dir, digest).restore();

            assertThat(messages(capture))
                    .containsExactly("Peekaboot errors: " + file()
                            + " comes from another Peekaboot version or starts with a damaged header; starting without"
                            + " it");
        }
        assertThat(digest.entries()).isEmpty();
        assertThat(file()).doesNotExist();
    }

    /** A power loss after the rename can leave the file empty; it reads as a damaged header. */
    @Test
    void anEmptyFileIsDeletedWithOneLine() throws IOException {
        Files.createFile(file());
        ErrorDigest digest = new ErrorDigest();

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            new ErrorDigestFile(dir, digest).restore();

            assertThat(messages(capture))
                    .containsExactly("Peekaboot errors: " + file()
                            + " comes from another Peekaboot version or starts with a damaged header; starting without"
                            + " it");
        }
        assertThat(digest.entries()).isEmpty();
        assertThat(file()).doesNotExist();
    }

    /** A foreign file that cannot be deleted still costs one INFO; the failed delete goes to DEBUG. */
    @Test
    void aForeignFileThatCannotBeDeletedSaysOneLine() throws IOException {
        Files.writeString(file(), "{\"version\":99}\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.DEBUG)) {
            new ErrorDigestFile(dir, new ErrorDigest()).restore();

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.INFO, Level.DEBUG);
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(file()).exists();
    }

    @Test
    void anUnreadableLineCostsOnlyItself() throws IOException {
        ErrorDigest written = new ErrorDigest();
        written.record(FailedSummaries.failed("t1").build());
        new ErrorDigestFile(dir, written).flush();
        Files.writeString(file(), "{\"route\":\"/broken\"\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ErrorDigest digest = new ErrorDigest();

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            new ErrorDigestFile(dir, digest).restore();

            assertThat(messages(capture))
                    .containsExactly(
                            "Peekaboot errors: skipped 1 unreadable lines in " + file() + "; restored the rest");
        }
        assertThat(digest.entries()).isEqualTo(written.entries());
    }

    /** A restore that skipped a line rewrites the file at the first flush, so the next start reads it clean. */
    @Test
    void theFirstFlushAfterSkippingALineRewritesTheFile() throws IOException {
        ErrorDigest written = new ErrorDigest();
        written.record(FailedSummaries.failed("t1").build());
        new ErrorDigestFile(dir, written).flush();
        List<String> clean = Files.readAllLines(file());
        Files.writeString(file(), "{\"route\":\"/broken\"\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ErrorDigestFile digestFile = new ErrorDigestFile(dir, new ErrorDigest());
        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            digestFile.restore();

            assertThat(messages(capture)).singleElement().asString().startsWith("Peekaboot errors: skipped 1");
        }

        digestFile.flush();

        assertThat(Files.readAllLines(file())).isEqualTo(clean);
    }

    /** A line the digest did not keep, here a second line for the same failure, is gone after the first flush. */
    @Test
    void theFirstFlushAfterDroppingALineRewritesTheFile() throws IOException {
        ErrorDigest written = new ErrorDigest();
        written.record(FailedSummaries.failed("t1").build());
        new ErrorDigestFile(dir, written).flush();
        List<String> clean = Files.readAllLines(file());
        Files.writeString(file(), clean.getLast() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ErrorDigestFile digestFile = new ErrorDigestFile(dir, new ErrorDigest());
        digestFile.restore();

        digestFile.flush();

        assertThat(Files.readAllLines(file())).isEqualTo(clean);
    }

    /** A line missing what an entry needs is refused, so a restored digest never holds an entry without its trace id. */
    @Test
    void aLineWithoutItsTraceIdIsUnreadable() throws IOException {
        Files.writeString(file(), """
                {"version":1}
                {"count":1,"firstSeen":"2026-11-06T10:00:00Z","lastSeen":"2026-11-06T10:00:00Z","runs":[]}
                """);
        ErrorDigest digest = new ErrorDigest();

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class, Level.INFO)) {
            new ErrorDigestFile(dir, digest).restore();

            assertThat(messages(capture))
                    .containsExactly(
                            "Peekaboot errors: skipped 1 unreadable lines in " + file() + "; restored the rest");
        }
        assertThat(digest.entries()).isEmpty();
    }

    @Test
    void anUnwritableDirectoryWarnsOnceAndStopsTrying() throws IOException {
        Path notADirectory = Files.writeString(dir.resolve("plain-file"), "x");
        ErrorDigest digest = new ErrorDigest();
        ErrorDigestFile digestFile = new ErrorDigestFile(notADirectory, digest);

        try (LogCapture capture = LogCapture.attach(ErrorDigestFile.class)) {
            digest.record(FailedSummaries.failed("t1").build());
            digestFile.flush();
            digest.record(FailedSummaries.failed("t2").build());
            digestFile.flush();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith(
                                "Peekaboot errors: cannot write " + notADirectory.resolve(ErrorDigestFile.FILE_NAME))
                        .endsWith("the error digest from here on will not survive a restart");
            });
        }
    }
}
