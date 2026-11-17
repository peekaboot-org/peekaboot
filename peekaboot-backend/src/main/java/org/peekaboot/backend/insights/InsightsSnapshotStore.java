package org.peekaboot.backend.insights;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.BackgroundLoad;
import org.peekaboot.backend.storage.OwnerOnlyFiles;
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
    public void beginLoad() {
        historyLoad.begin("peekaboot-insights-restore", () -> publish(load()));
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
     * {@link #beginLoad()} was never called - this must never throw, since it runs on a
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
        writeNow();
    }

    @Override
    public boolean persists() {
        return true;
    }

    void writeNow() {
        try {
            write();
        } catch (IOException e) {
            reportWriteFailure(e);
        }
    }

    private synchronized void write() throws IOException {
        RingCapture source = capture;
        if (source == null) {
            return;
        }
        // Read before capturing: a restore landing in between would vouch for rings captured without it.
        boolean restoredBeforeCapture = historyRestored.getAsBoolean();
        InsightsSnapshot snapshot = source.entriesAfter(new long[geometry.size()]);
        if (!replacesWhatIsThere(snapshot, restoredBeforeCapture)) {
            return;
        }
        OwnerOnlyFiles.replaceAtomically(file, stream -> {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream))) {
                InsightsSnapshotCodec.write(out, snapshot);
            }
        });
        writeOutage.succeeded();
    }

    private void reportWriteFailure(IOException e) {
        writeOutage.failed(
                () -> log.warn("Peekaboot insights: cannot write {}; history will not survive this restart", file, e));
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
        schedule.runEvery(interval.toMillis(), 0, boundary -> {
            try {
                write();
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    // stop()'s interrupt closed the channel; stop() writes the final snapshot itself
                    return;
                }
                reportWriteFailure(e);
            }
        });
    }

    private Optional<InsightsSnapshot> load() {
        if (!Files.exists(file) && !Files.exists(journal.file())) {
            return Optional.empty();
        }
        // Set before the parse, so a write that follows a restore this run gave up waiting
        // for still knows there is history on disk it never took over.
        unclaimedHistory = true;
        InsightsSnapshot snapshot = null;
        if (Files.exists(file)) {
            try {
                snapshot = readSnapshot();
            } catch (IOException | RuntimeException e) {
                log.info(
                        "Peekaboot insights: ignoring unreadable {} ({}); starting with empty history",
                        file,
                        e.toString());
                discard();
                return Optional.empty();
            }
        }
        Optional<InsightsSnapshot> history = journal.replayOnto(snapshot).history();
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
        return history;
    }

    private InsightsSnapshot readSnapshot() throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return InsightsSnapshotCodec.readBody(in, InsightsSnapshotCodec.readHeader(in));
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
