package org.peekaboot.backend.insights;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.OwnerOnlyFiles;
import org.peekaboot.backend.storage.StorageDirectory;
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
public final class InsightsSnapshotStore implements InsightsCollector.SnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(InsightsSnapshotStore.class);

    public static final String FILE_NAME = "insights.snapshot";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Duration WRITER_JOIN = Duration.ofSeconds(2);
    /** Clock skew a snapshot may carry and still be believed; anything beyond it is a stepped clock. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final Path file;
    private final Path temp;
    private final List<InsightsSnapshot.Level> geometry;
    private final Duration interval;
    private final Duration maxAge;
    private final CompletableFuture<Optional<InsightsSnapshot>> loaded = new CompletableFuture<>();

    private volatile Supplier<InsightsSnapshot> capture;
    private volatile BooleanSupplier historyRestored = () -> false;
    private volatile Thread writer;
    private volatile boolean writeFailureLogged;
    private volatile boolean unclaimedHistory;

    public InsightsSnapshotStore(Path file, List<InsightsSnapshot.Level> geometry, Duration interval, Duration maxAge) {
        this.file = file;
        this.temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        this.geometry = List.copyOf(geometry);
        this.interval = interval;
        this.maxAge = maxAge;
    }

    /** The store for {@code properties}' persistence settings, or null while storage is off. */
    static InsightsSnapshotStore create(StorageDirectory storage, InsightsProperties properties) {
        if (storage == null) {
            return null;
        }
        return storage.file(FILE_NAME)
                .map(path -> new InsightsSnapshotStore(
                        path,
                        geometry(properties),
                        properties.resolvePersistenceInterval(),
                        properties.resolvePersistenceMaxAge()))
                .orElse(null);
    }

    /** The ring shape a persisted snapshot has to match; endEpochMs and count play no part. */
    private static List<InsightsSnapshot.Level> geometry(InsightsProperties properties) {
        return properties.getLevels().stream()
                .map(level -> new InsightsSnapshot.Level(level.getInterval().toMillis(), level.getSize(), 0, 0))
                .toList();
    }

    /** Submits the parse; returns immediately, so no context refresh ever waits on a file. */
    public void beginLoad() {
        Thread.ofVirtual().name("peekaboot-insights-restore").start(() -> completeLoaded(this::load));
    }

    /**
     * Runs {@code source} and completes {@link #loaded} with what it returns, or with empty
     * however it ends. An Error {@code load()} does not catch - an OutOfMemoryError from a
     * pathological file, say - would otherwise leave every waiter parked for the full
     * {@link #awaitSnapshot} timeout, on a collector level thread the host application is
     * paying for. Package-private so a test can hand in a source that throws.
     */
    void completeLoaded(Supplier<Optional<InsightsSnapshot>> source) {
        try {
            loaded.complete(source.get());
        } finally {
            loaded.complete(Optional.empty());
        }
    }

    /**
     * The persisted rings, or empty if there are none, they did not arrive in time, or
     * {@link #beginLoad()} was never called - this must never throw, since it runs on a
     * collector level thread whose contract is to keep ticking regardless. An interrupt
     * restores the thread's flag and returns empty rather than lingering for the full
     * timeout, which is what lets shutdown cut a parked restore short.
     */
    @Override
    public Optional<InsightsSnapshot> awaitSnapshot(Duration timeout) {
        try {
            return loaded.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.info("Peekaboot insights: persisted history did not arrive in time; starting empty");
            return Optional.empty();
        }
    }

    /**
     * Starts the periodic writer against {@code capture}, the collector's current state.
     * {@code historyRestored} reports whether that collector has taken the persisted rings
     * over, which is what decides whether its state may replace them.
     */
    public void start(Supplier<InsightsSnapshot> capture, BooleanSupplier historyRestored) {
        this.historyRestored = historyRestored;
        this.capture = capture;
        Thread thread = Thread.ofVirtual().name("peekaboot-insights-snapshot").unstarted(this::runWriter);
        this.writer = thread;
        thread.start();
    }

    /**
     * Stops the writer and takes the final snapshot. Called after the collector has
     * stopped, so what it captures is quiesced; synchronous, because there is no later
     * to defer to - the JVM is on its way out.
     */
    public void stop() {
        Thread thread = writer;
        writer = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(WRITER_JOIN.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writeNow();
    }

    synchronized void writeNow() {
        Supplier<InsightsSnapshot> source = capture;
        if (source == null) {
            return;
        }
        InsightsSnapshot snapshot = source.get();
        if (!replacesWhatIsThere(snapshot)) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                OwnerOnlyFiles.createDirectories(parent);
            }
            try (DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(OwnerOnlyFiles.newOutputStream(temp)))) {
                InsightsSnapshotCodec.write(out, snapshot);
            }
            move();
        } catch (IOException e) {
            deleteTemp();
            if (!writeFailureLogged) {
                writeFailureLogged = true;
                log.warn("Peekaboot insights: cannot write {}; history will not survive this restart", file, e);
            }
        }
    }

    /**
     * Whether the rings behind {@code snapshot} are at least as complete as the file they
     * would overwrite. They are not while a persisted history is still on disk unapplied -
     * a run whose restore timed out on a busy disk holds a handful of samples and would
     * otherwise replace a full retention window with them. A run that found nothing to take
     * over has nothing to lose, and writes as soon as it has sampled at all.
     */
    private boolean replacesWhatIsThere(InsightsSnapshot snapshot) {
        return historyRestored.getAsBoolean()
                || (!unclaimedHistory && snapshot.levels().stream().anyMatch(level -> level.endEpochMs() > 0));
    }

    /** A half-written temporary is megabytes of nothing; the next write starts it from scratch anyway. */
    private void deleteTemp() {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.debug("Could not delete the partial insights snapshot {}", temp, e);
        }
    }

    private void move() throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runWriter() {
        long intervalMs = interval.toMillis();
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            long boundary = ((now / intervalMs) + 1) * intervalMs;
            try {
                Thread.sleep(boundary - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            writeNow();
        }
    }

    private Optional<InsightsSnapshot> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        // Set before the parse, so a write that follows a restore this run gave up waiting
        // for still knows there is history on disk it never took over.
        unclaimedHistory = true;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            InsightsSnapshotCodec.Header header = InsightsSnapshotCodec.readHeader(in);
            if (isImplausiblyDated(header)) {
                log.info(
                        "Peekaboot insights: {} is not dated within the last {}; starting with empty history",
                        file,
                        maxAge);
                discard();
                return Optional.empty();
            }
            if (!matchesConfiguredGeometry(header)) {
                log.info("Peekaboot insights: {} was written for other ring levels; starting with empty history", file);
                discard();
                return Optional.empty();
            }
            return Optional.of(InsightsSnapshotCodec.readBody(in, header));
        } catch (IOException | RuntimeException e) {
            log.info(
                    "Peekaboot insights: ignoring unreadable {} ({}); starting with empty history", file, e.toString());
            discard();
            return Optional.empty();
        }
    }

    /**
     * A snapshot is worth reading only while it is dated inside the window it would restore
     * into. Past the cutoff every restored sample would be an empty gap; ahead of now - a
     * clock stepped back, a backup restored over a newer file - it would roll every level's
     * {@code endEpochMs} into the future, so no gap is ever filled and the newest samples
     * keep timestamps that never arrive.
     */
    private boolean isImplausiblyDated(InsightsSnapshotCodec.Header header) {
        long now = System.currentTimeMillis();
        return header.writtenAtEpochMs() < now - maxAge.toMillis()
                || header.writtenAtEpochMs() > now + CLOCK_SKEW.toMillis();
    }

    /** Only shape matters here; how full the rings were and where they ended does not. */
    private boolean matchesConfiguredGeometry(InsightsSnapshotCodec.Header header) {
        if (header.levels().size() != geometry.size()) {
            return false;
        }
        for (int level = 0; level < geometry.size(); level++) {
            InsightsSnapshot.Level persisted = header.levels().get(level);
            InsightsSnapshot.Level configured = geometry.get(level);
            if (persisted.intervalMs() != configured.intervalMs() || persisted.size() != configured.size()) {
                return false;
            }
        }
        return true;
    }

    /** A file that failed once will fail every start; deleting it keeps that from repeating. */
    private void discard() {
        unclaimedHistory = false;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete the unusable insights snapshot {}", file, e);
        }
    }
}
