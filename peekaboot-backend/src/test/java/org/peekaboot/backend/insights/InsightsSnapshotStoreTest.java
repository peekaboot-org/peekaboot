package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.testsupport.LogCapture;

class InsightsSnapshotStoreTest {

    private static final List<InsightsSnapshot.Level> GEOMETRY = List.of(new InsightsSnapshot.Level(10_000, 90, 0, 0));

    @TempDir
    Path directory;

    private InsightsSnapshotStore store(Duration maxAge) {
        return new InsightsSnapshotStore(
                directory.resolve(InsightsSnapshotStore.FILE_NAME), GEOMETRY, Duration.ofHours(1), maxAge);
    }

    private static InsightsSnapshot snapshot(long writtenAtEpochMs, long intervalMs, int size) {
        return new InsightsSnapshot(
                writtenAtEpochMs,
                List.of(new InsightsSnapshot.Level(intervalMs, size, 20_000, 2)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0, 2.0}})));
    }

    /** One sample of {@code value}, the shape a run that restored nothing would capture. */
    private static InsightsSnapshot ownSamplesOnly(double value) {
        return new InsightsSnapshot(
                System.currentTimeMillis(),
                List.of(new InsightsSnapshot.Level(10_000, 90, 30_000, 1)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{value}})));
    }

    private Optional<InsightsSnapshot> loadWith(InsightsSnapshotStore store) {
        store.beginLoad();
        return store.awaitSnapshot(Duration.ofSeconds(5));
    }

    @Test
    void anAbsentFileIsSimplyNoHistory() {
        assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();
    }

    /**
     * An Error escaping the load - an OutOfMemoryError from a pathological file, say - must
     * not leave awaitSnapshot parked for the full timeout; the future has to be released
     * regardless.
     */
    @Test
    void anErrorEscapingTheLoadStillReleasesTheWaiters() {
        InsightsSnapshotStore store = store(Duration.ofDays(30));

        try {
            store.completeLoaded(() -> {
                throw new OutOfMemoryError("simulated");
            });
        } catch (OutOfMemoryError expected) {
            // completeLoaded's finally already released awaitSnapshot before rethrowing
        }

        long startNanos = System.nanoTime();
        assertThat(store.awaitSnapshot(Duration.ofSeconds(5))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void whatOneRunWritesTheNextRunReads() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);
        writer.stop();

        Optional<InsightsSnapshot> restored = loadWith(store(Duration.ofDays(30)));

        assertThat(restored).isPresent();
        assertThat(restored.get().series().get("cpu.process").get(0)[0]).containsExactly(1.0, 2.0);
        assertThat(directory.resolve("insights.snapshot.tmp")).doesNotExist();
    }

    @Test
    void aRunThatNeverSampledDoesNotReplaceGoodHistory() throws IOException {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);
        writer.stop();
        long size = Files.size(directory.resolve(InsightsSnapshotStore.FILE_NAME));

        InsightsSnapshotStore empty = store(Duration.ofDays(30));
        empty.start(
                () -> new InsightsSnapshot(
                        System.currentTimeMillis(), List.of(new InsightsSnapshot.Level(10_000, 90, 0, 0)), Map.of()),
                () -> false);
        empty.stop();

        assertThat(Files.size(directory.resolve(InsightsSnapshotStore.FILE_NAME)))
                .isEqualTo(size);
    }

    /**
     * A restore that never landed leaves the rings holding only this run's own samples,
     * which is less than the file already has.
     */
    @Test
    void aRunThatNeverTookThePersistedHistoryOverDoesNotReplaceIt() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);
        writer.stop();

        InsightsSnapshotStore second = store(Duration.ofDays(30));
        assertThat(loadWith(second)).isPresent();
        second.start(() -> ownSamplesOnly(9.0), () -> false);
        second.stop();

        assertThat(loadWith(store(Duration.ofDays(30))))
                .get()
                .extracting(restored -> restored.series().get("cpu.process").get(0)[0])
                .isEqualTo(new double[] {1.0, 2.0});
    }

    @Test
    void aRunThatTookThePersistedHistoryOverReplacesIt() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);
        writer.stop();

        InsightsSnapshotStore second = store(Duration.ofDays(30));
        assertThat(loadWith(second)).isPresent();
        second.start(() -> ownSamplesOnly(9.0), () -> true);
        second.stop();

        assertThat(loadWith(store(Duration.ofDays(30))))
                .get()
                .extracting(restored -> restored.series().get("cpu.process").get(0)[0])
                .isEqualTo(new double[] {9.0});
    }

    /** A write that fails part way must not leave megabytes of nothing in the user's home. */
    @Test
    void aWriteThatFailsLeavesNoPartialFileBehind() {
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        // a snapshot that contradicts its own header: the codec refuses it mid-write
        store.start(
                () -> new InsightsSnapshot(
                        System.currentTimeMillis(),
                        List.of(new InsightsSnapshot.Level(10_000, 90, 20_000, 3)),
                        Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0, 2.0}}))),
                () -> false);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.stop();

            assertThat(capture.appender().list).hasSize(1);
            assertThat(capture.appender().list.get(0).getLevel()).isEqualTo(Level.WARN);
        }
        assertThat(directory.resolve("insights.snapshot.tmp")).doesNotExist();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aSnapshotDatedInTheFutureIsDeletedUnread() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(
                () -> snapshot(System.currentTimeMillis() + Duration.ofDays(1).toMillis(), 10_000, 90), () -> false);
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aSnapshotOlderThanTheCutoffIsDeletedUnread() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(
                () -> snapshot(System.currentTimeMillis() - Duration.ofDays(31).toMillis(), 10_000, 90), () -> false);
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aReshapedRingGeometryDiscardsTheWholeFile() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(() -> snapshot(System.currentTimeMillis(), 30_000, 90), () -> false); // level 0 was 10s
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    /** Discarding a file is announced with one INFO line - deliberate; pinned here rather than reaching the console. */
    private static Optional<InsightsSnapshot> discardIsAnnounced(Supplier<Optional<InsightsSnapshot>> load) {
        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            Optional<InsightsSnapshot> restored = load.get();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("starting with empty history");
            });
            return restored;
        }
    }

    @Test
    void aCorruptFileIsReportedOnceAndCleanedUp() throws IOException {
        Path file = directory.resolve(InsightsSnapshotStore.FILE_NAME);
        Files.writeString(file, "this is not a snapshot");

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();

            assertThat(capture.appender().list).hasSize(1);
            assertThat(capture.appender().list.get(0).getFormattedMessage()).contains("insights");
        }
        assertThat(file).doesNotExist();
    }

    /** The rings describe the host application's runtime; the file is the owner's business and no one else's. */
    @Test
    void theSnapshotAndItsDirectoryAreReadableByTheOwnerAlone() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path stateDirectory = directory.resolve("state");
        InsightsSnapshotStore store = new InsightsSnapshotStore(
                stateDirectory.resolve(InsightsSnapshotStore.FILE_NAME),
                GEOMETRY,
                Duration.ofHours(1),
                Duration.ofDays(30));
        store.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);

        store.stop();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stateDirectory)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(stateDirectory.resolve(InsightsSnapshotStore.FILE_NAME))))
                .isEqualTo("rw-------");
    }

    @Test
    void aSymlinkPlantedAtTheTemporaryPathIsReplacedNotFollowed() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path victim = directory.resolve("victim");
        Files.writeString(victim, "untouched");
        Files.createSymbolicLink(directory.resolve("insights.snapshot.tmp"), victim);
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);

        store.stop();

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(loadWith(store(Duration.ofDays(30)))).isPresent();
    }

    @Test
    void anUnwritableDirectoryCostsOneWarningAndNothingElse() throws IOException {
        Path blocked = Files.createFile(directory.resolve("blocked"));

        InsightsSnapshotStore store = new InsightsSnapshotStore(
                blocked.resolve("insights.snapshot"), GEOMETRY, Duration.ofHours(1), Duration.ofDays(30));

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.start(() -> snapshot(System.currentTimeMillis(), 10_000, 90), () -> false);
            store.writeNow();
            store.stop();

            assertThat(capture.appender().list).hasSize(1);
            assertThat(capture.appender().list.get(0).getLevel()).isEqualTo(Level.WARN);
        }
    }
}
