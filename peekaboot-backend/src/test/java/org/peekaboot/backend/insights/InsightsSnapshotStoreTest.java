package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.testsupport.PosixPermissions;
import org.peekaboot.testsupport.LogCapture;

class InsightsSnapshotStoreTest {

    private static final List<InsightsSnapshot.Level> GEOMETRY = List.of(new InsightsSnapshot.Level(10_000, 90, 0, 0));
    private static final InsightsJournalCodec.Header JOURNAL_HEADER =
            new InsightsJournalCodec.Header(GEOMETRY, List.of("cpu.process"));
    private static final long NOW = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli();
    private static final IntervalBoundary FIXED_SCHEDULE = new IntervalBoundary(() -> NOW, Thread::sleep);

    @TempDir
    Path directory;

    private InsightsSnapshotStore store(Duration maxAge) {
        return new InsightsSnapshotStore(
                directory.resolve(InsightsSnapshotStore.FILE_NAME),
                GEOMETRY,
                Duration.ofHours(1),
                maxAge,
                FIXED_SCHEDULE);
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
                NOW,
                List.of(new InsightsSnapshot.Level(10_000, 90, 30_000, 1)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{value}})));
    }

    /** Three samples announced, two carried: the codec refuses it mid-write. */
    private static InsightsSnapshot contradictsItsOwnHeader() {
        return new InsightsSnapshot(
                NOW,
                List.of(new InsightsSnapshot.Level(10_000, 90, 20_000, 3)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0, 2.0}})));
    }

    private static InsightsSnapshot neverSampled() {
        return new InsightsSnapshot(NOW, List.of(new InsightsSnapshot.Level(10_000, 90, 0, 0)), Map.of());
    }

    private String cannotWriteWarning() {
        return "Peekaboot insights: cannot write " + directory.resolve(InsightsSnapshotStore.FILE_NAME)
                + "; history will not survive this restart";
    }

    private Optional<InsightsSnapshot> loadWith(InsightsSnapshotStore store) {
        store.beginLoad();
        return store.awaitSnapshot(Duration.ofSeconds(5));
    }

    static Stream<Arguments> takeOverOutcomes() {
        return Stream.of(
                Arguments.of(Named.of("history never taken over: the file stays", false), new double[] {1.0, 2.0}),
                Arguments.of(Named.of("history taken over: this run's samples replace it", true), new double[] {9.0}));
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
    void anAbsentFileIsSimplyNoHistory() {
        assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();
    }

    /**
     * An Error escaping the load - an OutOfMemoryError from a pathological file, say - must
     * not leave awaitSnapshot parked for the full timeout; the waiters have to be released
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

        // a zero timeout returns at once either way; only a load still pending announces it
        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            assertThat(store.awaitSnapshot(Duration.ZERO)).isEmpty();

            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** Shutdown interrupts a restore that is still parked; that is no late history and is not announced as one. */
    @Test
    void aWaitCutShortByAnInterruptIsNotAnnouncedAsLateHistory() {
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        Thread.currentThread().interrupt();

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            Optional<InsightsSnapshot> restored = store.awaitSnapshot(Duration.ofSeconds(5));
            // Reads the flag and clears it, so it does not reach the next test.
            boolean flagKept = Thread.interrupted();

            assertThat(restored).isEmpty();
            assertThat(flagKept).isTrue();
            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void whatOneRunWritesTheNextRunReads() throws IOException {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
        writer.stop();

        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();

        assertThat(restored.series().get("cpu.process").get(0)[0]).containsExactly(1.0, 2.0);
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries)
                    .containsExactlyInAnyOrder(directory.resolve(InsightsSnapshotStore.FILE_NAME), journal());
        }
    }

    @Test
    void aFoldLeavesTheSnapshotBesideAJournalWithoutRecords() throws IOException {
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> snapshot(NOW, 10_000, 90), () -> false);

        store.stop();

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal())))) {
            InsightsJournalCodec.Header header = InsightsJournalCodec.readHeader(in);
            assertThat(header).isEqualTo(JOURNAL_HEADER);
            assertThat(InsightsJournalCodec.readRecord(in, header)).isEmpty();
        }
    }

    /** The journal is the file that fails when it cannot be replaced, and the warning says so. */
    @Test
    void aFoldWhoseJournalCannotBeReplacedNamesTheJournal() throws IOException {
        Files.createDirectories(journal().resolve("occupied"));
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> snapshot(NOW, 10_000, 90), () -> false);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.stop();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: cannot write " + journal()
                                + "; history will not survive this restart");
            });
        }
        assertThat(loadWith(store(Duration.ofDays(30)))).isPresent();
    }

    /** The rings hold megabytes; once the collector has them, a second copy here is dead weight for the run. */
    @Test
    void theLoadedHistoryIsHandedOverOnce() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
        writer.stop();
        InsightsSnapshotStore store = store(Duration.ofDays(30));

        assertThat(loadWith(store)).isPresent();

        assertThat(store.awaitSnapshot(Duration.ZERO)).isEmpty();
    }

    /** The collector waits once and never comes back, so history that lands later would be held for nobody. */
    @Test
    void historyThatArrivesAfterTheWaitGaveUpIsNotKept() {
        InsightsSnapshotStore store = store(Duration.ofDays(30));

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            assertThat(store.awaitSnapshot(Duration.ZERO)).isEmpty();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: persisted history did not arrive in time; starting empty");
            });
        }
        store.completeLoaded(() -> Optional.of(snapshot(NOW, 10_000, 90)));

        assertThat(store.awaitSnapshot(Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void aRunThatNeverSampledDoesNotReplaceGoodHistory() throws IOException {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
        writer.stop();
        long size = Files.size(directory.resolve(InsightsSnapshotStore.FILE_NAME));

        InsightsSnapshotStore empty = store(Duration.ofDays(30));
        empty.start(ends -> neverSampled(), () -> false);
        empty.stop();

        assertThat(Files.size(directory.resolve(InsightsSnapshotStore.FILE_NAME)))
                .isEqualTo(size);
    }

    /**
     * A restore that never landed leaves the rings holding only this run's own samples,
     * which is less than the file already has; once the history was taken over, this run's
     * rings are the newer record and replace it.
     */
    @ParameterizedTest
    @MethodSource("takeOverOutcomes")
    void aRunReplacesThePersistedHistoryOnlyOnceItTookItOver(boolean historyRestored, double[] expected) {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
        writer.stop();

        InsightsSnapshotStore second = store(Duration.ofDays(30));
        assertThat(loadWith(second)).isPresent();
        second.start(ends -> ownSamplesOnly(9.0), () -> historyRestored);
        second.stop();

        assertThat(loadWith(store(Duration.ofDays(30))).orElseThrow())
                .extracting(restored -> restored.series().get("cpu.process").get(0)[0])
                .isEqualTo(expected);
    }

    /** A capture taken before the restore landed holds none of the history it would overwrite. */
    @Test
    void aCaptureTakenBeforeTheRestoreLandedDoesNotReplaceTheHistory() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
        writer.stop();

        InsightsSnapshotStore second = store(Duration.ofDays(30));
        assertThat(loadWith(second)).isPresent();
        AtomicBoolean restored = new AtomicBoolean();
        second.start(
                ends -> {
                    InsightsSnapshot preRestore = ownSamplesOnly(9.0);
                    restored.set(true);
                    return preRestore;
                },
                restored::get);
        second.stop();

        assertThat(loadWith(store(Duration.ofDays(30))).orElseThrow())
                .extracting(persisted -> persisted.series().get("cpu.process").get(0)[0])
                .isEqualTo(new double[] {1.0, 2.0});
    }

    /** A write that fails part way must not leave megabytes of nothing in the user's home. */
    @Test
    void aWriteThatFailsLeavesNoPartialFileBehind() {
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> contradictsItsOwnHeader(), () -> false);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.stop();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(cannotWriteWarning());
            });
        }
        assertThat(directory).isEmptyDirectory();
    }

    /** A disk that fills up twice in one run is reported twice. */
    @Test
    void aSecondWriteOutageIsReportedAfterAWriteSucceededInBetween() {
        Iterator<InsightsSnapshot> captures = List.of(
                        contradictsItsOwnHeader(),
                        snapshot(NOW, 10_000, 90),
                        contradictsItsOwnHeader(),
                        snapshot(NOW, 10_000, 90))
                .iterator();
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> captures.next(), () -> false);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.writeNow();
            store.writeNow();
            store.writeNow();
            store.stop();

            // the third write is an append, so the journal is the file it fails on
            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel)
                    .containsOnly(Level.WARN);
            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            cannotWriteWarning(),
                            "Peekaboot insights: cannot write " + journal()
                                    + "; history will not survive this restart");
        }
    }

    /** A write skipped for having nothing to save proves nothing about the disk. */
    @Test
    void aSkippedWriteDoesNotEndAWriteOutage() {
        Iterator<InsightsSnapshot> captures = List.of(
                        contradictsItsOwnHeader(), neverSampled(), contradictsItsOwnHeader(), neverSampled())
                .iterator();
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> captures.next(), () -> false);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.writeNow();
            store.writeNow();
            store.writeNow();
            store.stop();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(cannotWriteWarning());
            });
        }
    }

    /**
     * stop() interrupts the writer, and a file channel closes itself on an interrupt; a
     * scheduled write caught in flight is cancelled by the final write, not a failure.
     */
    @Test
    void stoppingDuringAScheduledWriteIsNotAFailure() throws InterruptedException {
        CountDownLatch scheduledWriteInFlight = new CountDownLatch(1);
        AtomicBoolean firstCapture = new AtomicBoolean(true);
        InsightsSnapshotStore store = new InsightsSnapshotStore(
                directory.resolve(InsightsSnapshotStore.FILE_NAME),
                GEOMETRY,
                Duration.ofHours(1),
                Duration.ofDays(30),
                new IntervalBoundary(() -> NOW, millis -> {}));

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.start(
                    ends -> {
                        if (firstCapture.getAndSet(false)) {
                            scheduledWriteInFlight.countDown();
                            while (!Thread.currentThread().isInterrupted()) {
                                LockSupport.park();
                            }
                        }
                        return snapshot(NOW, 10_000, 90);
                    },
                    () -> false);
            assertThat(scheduledWriteInFlight.await(5, TimeUnit.SECONDS)).isTrue();

            store.stop();

            assertThat(capture.appender().list).isEmpty();
        }
        assertThat(loadWith(store(Duration.ofDays(30)))).isPresent();
    }

    @Test
    void aSnapshotDatedInTheFutureIsDeleted() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW + Duration.ofDays(1).toMillis(), 10_000, 90), () -> false);
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aSnapshotOlderThanTheCutoffIsDeleted() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW - Duration.ofDays(31).toMillis(), 10_000, 90), () -> false);
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aReshapedRingGeometryDiscardsTheWholeFile() {
        InsightsSnapshotStore writer = store(Duration.ofDays(30));
        writer.start(ends -> snapshot(NOW, 30_000, 90), () -> false); // level 0 was 10s
        writer.stop();

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aCorruptFileIsReportedOnceAndCleanedUp() throws IOException {
        Path file = directory.resolve(InsightsSnapshotStore.FILE_NAME);
        Files.writeString(file, "this is not a snapshot");

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class, Level.INFO)) {
            assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .startsWith("Peekaboot insights: ignoring unreadable " + file + " (")
                        .endsWith("); starting with empty history");
            });
        }
        assertThat(file).doesNotExist();
    }

    /** The rings describe the host application's runtime; the file is the owner's business and no one else's. */
    @Test
    void theSnapshotAndItsDirectoryAreReadableByTheOwnerAlone() throws IOException {
        PosixPermissions.assumeSupported();
        Path stateDirectory = directory.resolve("state");
        InsightsSnapshotStore store = new InsightsSnapshotStore(
                stateDirectory.resolve(InsightsSnapshotStore.FILE_NAME),
                GEOMETRY,
                Duration.ofHours(1),
                Duration.ofDays(30),
                FIXED_SCHEDULE);
        store.start(ends -> snapshot(NOW, 10_000, 90), () -> false);

        store.stop();

        assertThat(PosixPermissions.of(stateDirectory)).isEqualTo("rwx------");
        assertThat(PosixPermissions.of(stateDirectory.resolve(InsightsSnapshotStore.FILE_NAME)))
                .isEqualTo("rw-------");
        assertThat(PosixPermissions.of(stateDirectory.resolve(InsightsJournal.FILE_NAME)))
                .isEqualTo("rw-------");
    }

    @Test
    void aSymlinkPlantedAtTheFormerTemporaryPathIsNeverWrittenThrough() throws IOException {
        PosixPermissions.assumeSupported();
        Path victim = directory.resolve("victim");
        Files.writeString(victim, "untouched");
        Files.createSymbolicLink(directory.resolve("insights.snapshot.tmp"), victim);
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        store.start(ends -> snapshot(NOW, 10_000, 90), () -> false);

        store.stop();

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(loadWith(store(Duration.ofDays(30)))).isPresent();
    }

    @Test
    void anUnwritableDirectoryCostsOneWarningAndNothingElse() throws IOException {
        Path blocked = Files.createFile(directory.resolve("blocked"));

        InsightsSnapshotStore store = new InsightsSnapshotStore(
                blocked.resolve("insights.snapshot"),
                GEOMETRY,
                Duration.ofHours(1),
                Duration.ofDays(30),
                FIXED_SCHEDULE);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class)) {
            store.start(ends -> snapshot(NOW, 10_000, 90), () -> false);
            store.writeNow();
            store.stop();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: cannot write " + blocked.resolve("insights.snapshot")
                                + "; history will not survive this restart");
            });
        }
    }

    @Test
    void theJournalsRecordsAreAppliedOnTopOfTheSnapshot() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0), entries(NOW, 50_000, 4.0, 5.0));

        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();

        assertThat(level0(restored)).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);
        assertThat(restored.levels()).singleElement().satisfies(level -> {
            assertThat(level.endEpochMs()).isEqualTo(50_000);
            assertThat(level.count()).isEqualTo(5);
        });
    }

    @Test
    void aJournalWithoutASnapshotRestoresOnItsOwn() throws IOException {
        writeJournal(JOURNAL_HEADER, entries(NOW, 40_000, 7.0, 8.0));

        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();

        assertThat(level0(restored)).containsExactly(7.0, 8.0);
        assertThat(restored.levels()).singleElement().satisfies(level -> {
            assertThat(level.endEpochMs()).isEqualTo(40_000);
            assertThat(level.count()).isEqualTo(2);
        });
        assertThat(InsightsSnapshot.sameGeometry(restored.levels(), GEOMETRY)).isTrue();
    }

    @Test
    void aJournalWithoutRecordsOrSnapshotIsNoHistoryAndBlocksNoWrite() throws IOException {
        writeJournal(JOURNAL_HEADER);

        assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();

        assertThat(journal()).doesNotExist();
        InsightsSnapshotStore second = store(Duration.ofDays(30));
        second.start(ends -> ownSamplesOnly(9.0), () -> false);
        second.stop();
        assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow())).containsExactly(9.0);
    }

    @Test
    void aJournalAloneIsHistoryARunMustTakeOverBeforeWriting() throws IOException {
        writeJournal(JOURNAL_HEADER, entries(NOW, 40_000, 7.0, 8.0));
        byte[] before = Files.readAllBytes(journal());
        InsightsSnapshotStore store = store(Duration.ofDays(30));
        assertThat(loadWith(store)).isPresent();

        store.start(ends -> ownSamplesOnly(9.0), () -> false);
        store.stop();

        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
        assertThat(Files.readAllBytes(journal())).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"a foreign magic,0,0", "a version 2,7,2"})
    void aForeignJournalIsDeletedAndTheSnapshotRestoredAlone(String ignored, int offset, int value) throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        byte[] bytes = journalBytes(JOURNAL_HEADER, entries(NOW, 30_000, 3.0));
        bytes[offset] = (byte) value;
        Files.write(journal(), bytes);

        try (LogCapture capture = LogCapture.attach(InsightsJournal.class, Level.INFO)) {
            assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow()))
                    .containsExactly(1.0, 2.0);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .startsWith("Peekaboot insights: ignoring unreadable " + journal());
            });
        }
        assertThat(journal()).doesNotExist();
    }

    @Test
    void aJournalForOtherRingLevelsThanTheSnapshotIsNotApplied() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        InsightsJournalCodec.Header otherLevels = new InsightsJournalCodec.Header(
                List.of(new InsightsSnapshot.Level(30_000, 90, 0, 0)), List.of("cpu.process"));
        InsightsSnapshot record = new InsightsSnapshot(
                NOW,
                List.of(new InsightsSnapshot.Level(30_000, 90, 60_000, 1)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{3.0}})));
        writeJournal(otherLevels, record);

        try (LogCapture capture = LogCapture.attach(InsightsJournal.class, Level.INFO)) {
            assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow()))
                    .containsExactly(1.0, 2.0);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("other ring levels");
            });
        }
        assertThat(journal()).exists();
    }

    @Test
    void aRecordCutByAKillEndsTheReplayAndKeepsWhatCameBefore() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        byte[] bytes = journalBytes(JOURNAL_HEADER, entries(NOW, 30_000, 3.0), entries(NOW, 40_000, 4.0));
        Files.write(journal(), Arrays.copyOf(bytes, bytes.length - 3));

        try (LogCapture capture = LogCapture.attach(InsightsJournal.class, Level.INFO)) {
            assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow()))
                    .containsExactly(1.0, 2.0, 3.0);

            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void aRecordWithAWrongChecksumEndsTheReplay() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        int headerBytes = journalBytes(JOURNAL_HEADER).length;
        int firstRecordBytes = journalBytes(JOURNAL_HEADER, entries(NOW, 30_000, 3.0)).length - headerBytes;
        byte[] bytes = journalBytes(
                JOURNAL_HEADER, entries(NOW, 30_000, 3.0), entries(NOW, 40_000, 4.0), entries(NOW, 50_000, 5.0));
        bytes[headerBytes + firstRecordBytes + Integer.BYTES]++;
        Files.write(journal(), bytes);

        assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow())).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void aHoleEndsTheReplay() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0), entries(NOW, 60_000, 6.0), entries(NOW, 70_000, 7.0));

        assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow())).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void aKillBetweenTheTwoReplacesOfAFoldAppliesNothingTwice() throws IOException {
        writeSnapshot(snapshotEndingAt50s());
        long before = NOW - 60_000;
        writeJournal(JOURNAL_HEADER, entries(before, 40_000, 3.0, 4.0), entries(before, 50_000, 5.0));

        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();

        assertThat(level0(restored)).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);
        assertThat(restored.levels().get(0).endEpochMs()).isEqualTo(50_000);
        assertThat(restored.writtenAtEpochMs()).isEqualTo(NOW);
    }

    @Test
    void aStaleSnapshotTheJournalBringsUpToDateIsRestored() throws IOException {
        writeSnapshot(snapshot(NOW - Duration.ofDays(31).toMillis(), 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0));

        assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow())).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void aHistoryStillTooOldAfterTheJournalTakesBothFilesWithIt() throws IOException {
        long stale = NOW - Duration.ofDays(31).toMillis();
        writeSnapshot(snapshot(stale, 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(stale, 30_000, 3.0));

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
        assertThat(journal()).doesNotExist();
    }

    @Test
    void aCorruptSnapshotTakesTheJournalWithIt() throws IOException {
        Files.writeString(directory.resolve(InsightsSnapshotStore.FILE_NAME), "this is not a snapshot");
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0));

        assertThat(loadWith(store(Duration.ofDays(30)))).isEmpty();

        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).doesNotExist();
        assertThat(journal()).doesNotExist();
    }

    @Test
    void aJournalForOtherRingLevelsThanConfiguredTakesBothFilesWithIt() throws IOException {
        InsightsJournalCodec.Header otherLevels = new InsightsJournalCodec.Header(
                List.of(new InsightsSnapshot.Level(30_000, 90, 0, 0)), List.of("cpu.process"));
        InsightsSnapshot record = new InsightsSnapshot(
                NOW,
                List.of(new InsightsSnapshot.Level(30_000, 90, 60_000, 1)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{3.0}})));
        writeJournal(otherLevels, record);

        assertThat(discardIsAnnounced(() -> loadWith(store(Duration.ofDays(30)))))
                .isEmpty();
        assertThat(journal()).doesNotExist();
    }

    @Test
    void anAppendWritesOnlyTheEntriesSinceTheLastWrite() throws IOException {
        SeriesRings rings = rings(GEOMETRY);
        InsightsSnapshotStore store = startedManualStore(GEOMETRY, rings, () -> false);
        tick(rings, 10_000, 1.0);
        tick(rings, 20_000, 2.0);
        tick(rings, 30_000, 3.0);
        store.writeNow();
        tick(rings, 40_000, 4.0);
        tick(rings, 50_000, 5.0);
        byte[] snapshotBefore = Files.readAllBytes(snapshotFile());
        long journalBefore = Files.size(journal());

        store.writeNow();

        assertThat(Files.readAllBytes(snapshotFile())).isEqualTo(snapshotBefore);
        assertThat(Files.size(journal()) - journalBefore).isEqualTo(4 + 8 + 12 + 2 * 8 + 4);
        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();
        assertThat(level0(restored)).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);
        assertThat(restored.levels().get(0).endEpochMs()).isEqualTo(50_000);
    }

    /** What 1.3.0 left behind: a snapshot and no journal. */
    @Test
    void aStartThatFoundOnlyASnapshotContinuesItThroughTheJournal() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        byte[] snapshotBefore = Files.readAllBytes(snapshotFile());
        InsightsSnapshotStore store = manualStore(GEOMETRY);
        SeriesRings rings = restoredRings(store);
        tick(rings, 30_000, 3.0);
        store.start(ends -> rings.captureAfter(NOW, ends), () -> true);

        store.writeNow();

        assertThat(Files.readAllBytes(snapshotFile())).isEqualTo(snapshotBefore);
        assertThat(journalRecords()).singleElement().satisfies(record -> {
            assertThat(level0(record)).containsExactly(3.0);
            assertThat(record.levels().get(0).endEpochMs()).isEqualTo(30_000);
        });
        assertThat(level0(loadWith(store(Duration.ofDays(30))).orElseThrow())).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void aFoundJournalWithoutRecordsIsReplacedRatherThanAppendedTo() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        writeJournal(new InsightsJournalCodec.Header(GEOMETRY, List.of("old.series")));
        InsightsSnapshotStore store = manualStore(GEOMETRY);
        SeriesRings rings = restoredRings(store);
        tick(rings, 30_000, 3.0);
        store.start(ends -> rings.captureAfter(NOW, ends), () -> true);

        try (LogCapture capture = LogCapture.attach(InsightsSnapshotStore.class);
                LogCapture journalCapture = LogCapture.attach(InsightsJournal.class)) {
            store.writeNow();

            assertThat(capture.appender().list).isEmpty();
            assertThat(journalCapture.appender().list).isEmpty();
        }
        assertThat(journalTable()).containsExactly("cpu.process");
        assertThat(journalRecords()).hasSize(1);
    }

    @Test
    void theFirstWriteFoldsWhenTheStartAppliedJournalRecords() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0));
        InsightsSnapshotStore store = manualStore(GEOMETRY);
        SeriesRings rings = restoredRings(store);
        tick(rings, 40_000, 4.0);
        store.start(ends -> rings.captureAfter(NOW, ends), () -> true);

        store.writeNow();

        assertThat(journalRecords()).isEmpty();
        assertThat(level0(snapshotOnDisk())).containsExactly(1.0, 2.0, 3.0, 4.0);
    }

    @Test
    void theJournalFoldsOnceItIsAsLargeAsTheSnapshot() throws IOException {
        SeriesRings rings = rings(GEOMETRY);
        InsightsSnapshotStore store = startedManualStore(GEOMETRY, rings, () -> false);
        tick(rings, 10_000, 1.0);
        store.writeNow();
        tick(rings, 20_000, 2.0);
        store.writeNow();
        assertThat(journalRecords()).hasSize(1);
        assertThat(Files.size(journal())).isGreaterThanOrEqualTo(Files.size(snapshotFile()));
        tick(rings, 30_000, 3.0);

        store.writeNow();

        assertThat(journalRecords()).isEmpty();
        assertThat(level0(snapshotOnDisk())).hasSize(3);
    }

    @Test
    void stopFoldsTheJournalIntoTheSnapshot() throws IOException {
        SeriesRings rings = rings(GEOMETRY);
        InsightsSnapshotStore store = startedManualStore(GEOMETRY, rings, () -> false);
        for (int tick = 1; tick <= 5; tick++) {
            tick(rings, tick * 10_000L, tick);
        }
        store.writeNow();
        tick(rings, 60_000, 6.0);
        store.writeNow();
        assertThat(journalRecords()).hasSize(1);
        assertThat(Files.size(journal())).isLessThan(Files.size(snapshotFile()));

        store.stop();

        assertThat(journalRecords()).isEmpty();
        assertThat(level0(snapshotOnDisk())).hasSize(6);
    }

    @Test
    void aKillAfterAnAppendLosesOnlyWhatCameAfterIt() {
        SeriesRings rings = rings(GEOMETRY);
        InsightsSnapshotStore store = startedManualStore(GEOMETRY, rings, () -> false);
        for (int tick = 1; tick <= 5; tick++) {
            tick(rings, tick * 10_000L, tick);
        }
        store.writeNow();
        tick(rings, 60_000, 6.0);
        tick(rings, 70_000, 7.0);
        store.writeNow();
        tick(rings, 80_000, 8.0);

        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();

        assertThat(level0(restored)).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0);
        assertThat(restored.levels().get(0).endEpochMs()).isEqualTo(70_000);
    }

    @Test
    void aDowntimeLongerThanTheRingIsJournaledAsAFullRing() throws IOException {
        SeriesRings rings = rings(GEOMETRY);
        InsightsSnapshotStore store = startedManualStore(GEOMETRY, rings, () -> false);
        tick(rings, 10_000, 1.0);
        store.writeNow();
        long afterDowntime = 10_000 + 200 * 10_000L;
        tick(rings, afterDowntime, 9.0);

        store.writeNow();

        assertThat(journalRecords())
                .singleElement()
                .satisfies(record -> assertThat(record.levels().get(0).count()).isEqualTo(90));
        InsightsSnapshot restored = loadWith(store(Duration.ofDays(30))).orElseThrow();
        assertThat(level0(restored)).hasSize(90).endsWith(9.0);
        assertThat(restored.levels().get(0).endEpochMs()).isEqualTo(afterDowntime);
    }

    @Test
    void aLevelThatHasNotRolledUpYetIsJournaledOnceItHas() throws IOException {
        List<InsightsSnapshot.Level> twoLevels =
                List.of(new InsightsSnapshot.Level(10_000, 90, 0, 0), new InsightsSnapshot.Level(60_000, 10, 0, 0));
        SeriesRings rings = rings(twoLevels);
        InsightsSnapshotStore store = startedManualStore(twoLevels, rings, () -> false);
        for (int tick = 1; tick <= 6; tick++) {
            tick(rings, tick * 10_000L, tick);
        }
        store.writeNow();
        tick(rings, 70_000, 7.0);

        store.writeNow();

        rings.appendStats(1, 60_000, Map.of("cpu.process", AggregateStats.of(new double[] {1.0, 2.0})));
        tick(rings, 80_000, 8.0);
        store.writeNow();

        List<InsightsSnapshot> records = journalRecords();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).levels().get(1).count()).isZero();
        assertThat(records.get(0).levels().get(1).endEpochMs()).isZero();
        assertThat(records.get(1).levels().get(0).count()).isEqualTo(1);
        assertThat(records.get(1).levels().get(1).count()).isEqualTo(1);
        InsightsSnapshotStore reader = new InsightsSnapshotStore(
                snapshotFile(), twoLevels, Duration.ofHours(1), Duration.ofDays(30), FIXED_SCHEDULE);
        InsightsSnapshot restored = loadWith(reader).orElseThrow();
        assertThat(restored.levels().get(1).count()).isEqualTo(1);
        assertThat(restored.levels().get(1).endEpochMs()).isEqualTo(60_000);
        assertThat(restored.levels().get(0).count()).isEqualTo(8);
    }

    @Test
    void aRunWhoseRestoreNeverLandedWritesNeitherFile() throws IOException {
        writeSnapshot(snapshot(NOW, 10_000, 90));
        writeJournal(JOURNAL_HEADER, entries(NOW, 30_000, 3.0));
        byte[] snapshotBefore = Files.readAllBytes(snapshotFile());
        byte[] journalBefore = Files.readAllBytes(journal());
        InsightsSnapshotStore store = manualStore(GEOMETRY);
        assertThat(loadWith(store)).isPresent();
        SeriesRings rings = rings(GEOMETRY);
        store.start(ends -> rings.captureAfter(NOW, ends), () -> false);
        tick(rings, 40_000, 4.0);

        store.writeNow();
        store.stop();

        assertThat(Files.readAllBytes(snapshotFile())).isEqualTo(snapshotBefore);
        assertThat(Files.readAllBytes(journal())).isEqualTo(journalBefore);
    }

    private Path snapshotFile() {
        return directory.resolve(InsightsSnapshotStore.FILE_NAME);
    }

    private static List<InsightsProperties.Level> ringLevels(List<InsightsSnapshot.Level> geometry) {
        return geometry.stream()
                .map(level -> InsightsProperties.Level.of(Duration.ofMillis(level.intervalMs()), level.size()))
                .toList();
    }

    private static SeriesRings rings(List<InsightsSnapshot.Level> geometry) {
        return new SeriesRings(ringLevels(geometry), List.of("cpu.process"));
    }

    private static void tick(SeriesRings rings, long epochMs, double value) {
        rings.appendTick(epochMs, Map.of("cpu.process", value));
    }

    /** A store whose writer thread ends at once, so it writes only in writeNow() and stop(). */
    private InsightsSnapshotStore manualStore(List<InsightsSnapshot.Level> geometry) {
        return new InsightsSnapshotStore(
                snapshotFile(),
                geometry,
                Duration.ofHours(1),
                Duration.ofDays(30),
                new IntervalBoundary(() -> NOW, millis -> {
                    throw new InterruptedException();
                }));
    }

    private InsightsSnapshotStore startedManualStore(
            List<InsightsSnapshot.Level> geometry, SeriesRings rings, BooleanSupplier historyRestored) {
        InsightsSnapshotStore store = manualStore(geometry);
        store.start(ends -> rings.captureAfter(NOW, ends), historyRestored);
        return store;
    }

    /** Loads what the store finds on disk into fresh rings, as the collector does. */
    private SeriesRings restoredRings(InsightsSnapshotStore store) {
        SeriesRings rings = rings(GEOMETRY);
        rings.restore(loadWith(store).orElseThrow());
        return rings;
    }

    private List<InsightsSnapshot> journalRecords() throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal())))) {
            InsightsJournalCodec.Header header = InsightsJournalCodec.readHeader(in);
            List<InsightsSnapshot> records = new ArrayList<>();
            for (Optional<InsightsSnapshot> record = InsightsJournalCodec.readRecord(in, header);
                    record.isPresent();
                    record = InsightsJournalCodec.readRecord(in, header)) {
                records.add(record.get());
            }
            return records;
        }
    }

    private List<String> journalTable() throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal())))) {
            return InsightsJournalCodec.readHeader(in).seriesIds();
        }
    }

    /** The snapshot file alone, without the journal. */
    private InsightsSnapshot snapshotOnDisk() throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile())))) {
            return InsightsSnapshotCodec.readBody(in, InsightsSnapshotCodec.readHeader(in));
        }
    }

    private Path journal() {
        return directory.resolve(InsightsJournal.FILE_NAME);
    }

    private void writeSnapshot(InsightsSnapshot snapshot) throws IOException {
        try (DataOutputStream out =
                new DataOutputStream(Files.newOutputStream(directory.resolve(InsightsSnapshotStore.FILE_NAME)))) {
            InsightsSnapshotCodec.write(out, snapshot);
        }
    }

    private static byte[] journalBytes(InsightsJournalCodec.Header header, InsightsSnapshot... records)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InsightsJournalCodec.writeHeader(new DataOutputStream(bytes), header);
        for (InsightsSnapshot record : records) {
            bytes.write(InsightsJournalCodec.encodeRecord(header, record));
        }
        return bytes.toByteArray();
    }

    private void writeJournal(InsightsJournalCodec.Header header, InsightsSnapshot... records) throws IOException {
        Files.write(journal(), journalBytes(header, records));
    }

    /** A record of {@code values} ending at {@code end}, the entries a journal appends to level 0. */
    private static InsightsSnapshot entries(long writtenAt, long end, double... values) {
        return new InsightsSnapshot(
                writtenAt,
                List.of(new InsightsSnapshot.Level(10_000, 90, end, values.length)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {values})));
    }

    private static double[] level0(InsightsSnapshot history) {
        return history.series().get("cpu.process").get(0)[0];
    }

    private static InsightsSnapshot snapshotEndingAt50s() {
        return new InsightsSnapshot(
                NOW,
                List.of(new InsightsSnapshot.Level(10_000, 90, 50_000, 5)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0, 2.0, 3.0, 4.0, 5.0}})));
    }
}
