package org.peekaboot.backend.insights;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.BackgroundLoad;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.backend.storage.WriteOutage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The insights snapshot file: parsed in the background so no application waits for it
 * at startup, written atomically on a cadence and once more at shutdown.
 *
 * <p>A snapshot is a cache. Anything wrong with it - unreadable, from another schema,
 * older than the retention it would restore into, or shaped for a ring geometry the
 * configuration has since changed - costs the history and nothing else: the file is
 * deleted, the rings start empty, and the application never notices.
 */
final class InsightsSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(InsightsSnapshotStore.class);

    static final String FILE_NAME = "insights.snapshot";
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final Path file;
    private final List<InsightsSnapshot.Level> geometry;
    private final InsightsJournal journal;
    private final Duration interval;
    private final Duration maxAge;
    private final IntervalBoundary schedule;
    private final BackgroundLoad historyLoad = new BackgroundLoad();

    private final Object persistedLock = new Object();
    /** What the load found, until {@link #awaitSnapshot} takes it; guarded by {@link #persistedLock}. */
    private Optional<InsightsSnapshot> persisted = Optional.empty();
    /** Whether {@link #awaitSnapshot} has returned, so nobody is left to take a later load; same guard. */
    private boolean waitEnded;

    private volatile RingCapture capture;
    private volatile BooleanSupplier historyRestored = () -> false;
    private volatile Thread writer;
    private final WriteOutage writeOutage = new WriteOutage();
    private volatile boolean unclaimedHistory;

    // The four fields below track the writes and are guarded by this store's monitor.
    private long[] persistedEnds;
    private long snapshotBytes;
    private long journalBytes;
    /** The next write folds: the start found journal records or no snapshot, or the last write failed. */
    private boolean foldNext = true;

    InsightsSnapshotStore(Path file, List<InsightsSnapshot.Level> geometry, Duration interval, Duration maxAge) {
        this(file, geometry, interval, maxAge, new IntervalBoundary(System::currentTimeMillis, Thread::sleep));
    }

    /**
     * {@code schedule} is the writer's cadence and the clock a file's age is judged against;
     * tests build it on a fixed clock.
     */
    InsightsSnapshotStore(
            Path file,
            List<InsightsSnapshot.Level> geometry,
            Duration interval,
            Duration maxAge,
            IntervalBoundary schedule) {
        this.file = file;
        this.geometry = List.copyOf(geometry);
        this.journal = new InsightsJournal(file.resolveSibling(InsightsJournal.FILE_NAME), geometry);
        this.interval = interval;
        this.maxAge = maxAge;
        this.schedule = schedule;
        this.persistedEnds = new long[geometry.size()];
    }

    /**
     * The store for {@code properties}' persistence settings, or {@link SnapshotStore#NONE} where
     * this instance writes no file.
     */
    static SnapshotStore create(StorageDirectory storage, InsightsProperties properties) {
        if (storage == null) {
            return NONE;
        }
        return storage.file(FILE_NAME)
                .<SnapshotStore>map(path -> new InsightsSnapshotStore(
                        path,
                        geometry(properties),
                        properties.getPersistence().getInterval(),
                        properties.resolvePersistenceMaxAge()))
                .orElse(NONE);
    }

    /** The ring shape a persisted snapshot has to match; endEpochMs and count play no part. */
    private static List<InsightsSnapshot.Level> geometry(InsightsProperties properties) {
        return properties.getLevels().stream()
                .map(level -> new InsightsSnapshot.Level(level.intervalMillis(), level.getSize(), 0, 0))
                .toList();
    }

    @Override
    public void beginLoad(Set<String> seriesIds) {
        historyLoad.begin("peekaboot-insights-restore", () -> publish(load(seriesIds)));
    }

    /**
     * Runs {@code source} on the caller's thread and publishes what it returns, releasing the
     * waiters however it ends. Package-private so a test can hand in a source that throws.
     */
    void completeLoaded(Supplier<Optional<InsightsSnapshot>> source) {
        historyLoad.run(() -> publish(source.get()));
    }

    private void publish(Optional<InsightsSnapshot> found) {
        synchronized (persistedLock) {
            if (!waitEnded) {
                persisted = found;
            }
        }
    }

    /**
     * The persisted rings, or empty if there are none, they did not arrive in time, or
     * {@link #beginLoad} was never called - this must never throw, since it runs on a
     * collector level thread whose contract is to keep ticking regardless. An interrupt
     * restores the thread's flag and returns empty rather than lingering for the full
     * timeout, which is what lets shutdown cut a parked restore short.
     *
     * <p>The rings are handed over once. The collector copies them into its own, so the
     * store holds none after this returns, and a load that ends later is dropped.
     */
    @Override
    public Optional<InsightsSnapshot> awaitSnapshot(Duration timeout) {
        boolean arrived = historyLoad.await(timeout);
        if (!arrived && !Thread.currentThread().isInterrupted()) {
            log.info("Peekaboot insights: persisted history did not arrive in time; starting empty");
        }
        Optional<InsightsSnapshot> handedOver = takePersisted();
        return arrived ? handedOver : Optional.empty();
    }

    private Optional<InsightsSnapshot> takePersisted() {
        synchronized (persistedLock) {
            Optional<InsightsSnapshot> taken = persisted;
            persisted = Optional.empty();
            waitEnded = true;
            return taken;
        }
    }

    @Override
    public void start(RingCapture capture, BooleanSupplier historyRestored) {
        this.historyRestored = historyRestored;
        this.capture = capture;
        Thread thread = Thread.ofVirtual().name("peekaboot-insights-snapshot").unstarted(this::runWriter);
        this.writer = thread;
        thread.start();
    }

    /**
     * Synchronous, because there is no later to defer to - the JVM is on its way out. What
     * it captures is quiesced, since the collector has stopped by then.
     */
    @Override
    public void stop() {
        Thread thread = writer;
        writer = null;
        if (thread != null) {
            WorkerThreads.stopAndJoin(List.of(thread));
        }
        write(true);
    }

    @Override
    public boolean persists() {
        return true;
    }

    void writeNow() {
        write(false);
    }

    private synchronized void write(boolean stopping) {
        RingCapture source = capture;
        if (source == null) {
            return;
        }
        Path step = file;
        try {
            // Read before capturing: a restore landing in between would vouch for rings captured without it.
            boolean restoredBeforeCapture = historyRestored.getAsBoolean();
            boolean fold = stopping || mustFold();
            InsightsSnapshot captured = source.entriesAfter(fold ? new long[geometry.size()] : persistedEnds.clone());
            if (!fold && !continuesJournal(captured)) {
                fold = true;
                captured = source.entriesAfter(new long[geometry.size()]);
            }
            if (!replacesWhatIsThere(captured, restoredBeforeCapture)) {
                return;
            }
            List<String> seriesIds = List.copyOf(captured.series().keySet());
            if (fold) {
                snapshotBytes = InsightsSnapshotFile.replace(file, captured);
                step = journal.file();
                journalBytes = journal.start(seriesIds);
            } else {
                step = journal.file();
                appendToJournal(captured, seriesIds);
            }
            persistedEnds = levelEnds(captured);
            foldNext = false;
            writeOutage.succeeded();
        } catch (IOException e) {
            // A failed append can leave a cut record and a failed fold an old journal; only a fold repairs either.
            foldNext = true;
            reportWriteFailure(stopping, step, e);
        }
    }

    /** The caller holds this store's monitor, which guards the fields read here. */
    private boolean mustFold() {
        return foldNext || journalBytes >= snapshotBytes;
    }

    private void appendToJournal(InsightsSnapshot captured, List<String> seriesIds) throws IOException {
        if (journal.seriesIds() == null) {
            journalBytes = journal.start(seriesIds);
        }
        journalBytes += journal.append(captured);
    }

    /** Whether appending {@code captured} keeps the journal this run started readable and in date order. */
    private boolean continuesJournal(InsightsSnapshot captured) {
        List<String> table = journal.seriesIds();
        if (table != null && !table.equals(List.copyOf(captured.series().keySet()))) {
            return false;
        }
        long[] ends = levelEnds(captured);
        for (int level = 0; level < ends.length; level++) {
            if (ends[level] < persistedEnds[level]) {
                return false;
            }
        }
        return true;
    }

    private static long[] levelEnds(InsightsSnapshot snapshot) {
        return snapshot.levels().stream()
                .mapToLong(InsightsSnapshot.Level::endEpochMs)
                .toArray();
    }

    private void reportWriteFailure(boolean stopping, Path failedFile, IOException e) {
        if (!stopping && Thread.currentThread().isInterrupted()) {
            // stop()'s interrupt closed the channel; stop() writes the final snapshot itself
            return;
        }
        writeOutage.failed(() ->
                log.warn("Peekaboot insights: cannot write {}; history will not survive this restart", failedFile, e));
    }

    /**
     * Whether the rings behind {@code snapshot} are at least as complete as the file they
     * would overwrite. They are not while a persisted history is still on disk unapplied -
     * a run whose restore timed out on a busy disk holds a handful of samples and would
     * otherwise replace a full retention window with them. A run that found nothing to take
     * over has nothing to lose, and writes as soon as it has sampled at all.
     */
    private boolean replacesWhatIsThere(InsightsSnapshot snapshot, boolean restoredBeforeCapture) {
        return restoredBeforeCapture
                || (!unclaimedHistory && snapshot.levels().stream().anyMatch(level -> level.endEpochMs() > 0));
    }

    private void runWriter() {
        schedule.runEvery(interval.toMillis(), 0, boundary -> write(false));
    }

    private Optional<InsightsSnapshot> load(Set<String> seriesIds) {
        if (!Files.exists(file) && !Files.exists(journal.file())) {
            return Optional.empty();
        }
        // Set before the parse, so a write that follows a restore this run gave up waiting
        // for still knows there is history on disk it never took over.
        unclaimedHistory = true;
        InsightsSnapshot snapshot = null;
        if (Files.exists(file)) {
            try {
                snapshot = InsightsSnapshotFile.read(file);
            } catch (IOException | RuntimeException e) {
                log.info(
                        "Peekaboot insights: ignoring unreadable {} ({}); starting with empty history",
                        file,
                        e.toString());
                discard();
                return Optional.empty();
            }
        }
        InsightsJournal.Replay replay = journal.replayOnto(snapshot, seriesIds);
        Optional<InsightsSnapshot> history = replay.history();
        if (history.isEmpty()) {
            discard();
            return Optional.empty();
        }
        if (isImplausiblyDated(history.get().writtenAtEpochMs())) {
            log.info(
                    "Peekaboot insights: {} is not dated within the last {}; starting with empty history",
                    file,
                    maxAge);
            discard();
            return Optional.empty();
        }
        if (!matchesConfiguredGeometry(history.get().levels())) {
            log.info("Peekaboot insights: {} was written for other ring levels; starting with empty history", file);
            discard();
            return Optional.empty();
        }
        takeOver(history.get(), snapshot == null || replay.appliedRecords());
        return history;
    }

    /** Hands the writes what the load found, so they continue from it instead of rewriting it. */
    private synchronized void takeOver(InsightsSnapshot history, boolean foldFirst) {
        persistedEnds = levelEnds(history);
        foldNext = foldFirst;
        snapshotBytes = snapshotSize();
    }

    /** Zero when the size cannot be read, which makes every write a fold until one has replaced the snapshot. */
    private long snapshotSize() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            log.debug("Could not read the size of the insights snapshot {}", file, e);
            return 0;
        }
    }

    /**
     * A snapshot is worth reading only while it is dated inside the window it would restore
     * into. Past the cutoff every restored sample would be an empty gap; ahead of now by
     * more than {@link #CLOCK_SKEW} - a clock stepped back, a backup restored over a newer
     * file - it would roll every level's {@code endEpochMs} into the future, so no gap is
     * ever filled and the newest samples keep timestamps that never arrive.
     */
    private boolean isImplausiblyDated(long writtenAtEpochMs) {
        long now = schedule.now();
        return writtenAtEpochMs < now - maxAge.toMillis() || writtenAtEpochMs > now + CLOCK_SKEW.toMillis();
    }

    /** Only shape matters here; how full the rings were and where they ended does not. */
    private boolean matchesConfiguredGeometry(List<InsightsSnapshot.Level> persistedLevels) {
        return InsightsSnapshot.sameGeometry(persistedLevels, geometry);
    }

    /** A file that failed once will fail every start; deleting both keeps that from repeating. */
    private void discard() {
        unclaimedHistory = false;
        for (Path unusable : List.of(file, journal.file())) {
            try {
                Files.deleteIfExists(unusable);
            } catch (IOException e) {
                log.debug("Could not delete the unusable insights file {}", unusable, e);
            }
        }
    }
}
