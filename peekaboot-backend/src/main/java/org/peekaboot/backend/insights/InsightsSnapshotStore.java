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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
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

    private final Path file;
    private final Path temp;
    private final List<InsightsSnapshot.Level> geometry;
    private final Duration interval;
    private final Duration maxAge;
    private final CompletableFuture<Optional<InsightsSnapshot>> loaded = new CompletableFuture<>();

    private volatile Supplier<InsightsSnapshot> capture;
    private volatile Thread writer;
    private volatile boolean writeFailureLogged;

    public InsightsSnapshotStore(Path file, List<InsightsSnapshot.Level> geometry, Duration interval, Duration maxAge) {
        this.file = file;
        this.temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        this.geometry = List.copyOf(geometry);
        this.interval = interval;
        this.maxAge = maxAge;
    }

    /** Submits the parse; returns immediately, so no context refresh ever waits on a file. */
    public void beginLoad() {
        Thread.ofVirtual().name("peekaboot-insights-restore").start(() -> loaded.complete(load()));
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
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            log.info("Peekaboot insights: persisted history did not arrive in time; starting empty");
            return Optional.empty();
        }
    }

    /** Starts the periodic writer against {@code capture}, the collector's current state. */
    public void start(Supplier<InsightsSnapshot> capture) {
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

    void writeNow() {
        Supplier<InsightsSnapshot> source = capture;
        if (source == null) {
            return;
        }
        InsightsSnapshot snapshot = source.get();
        // A run that never sampled and restored nothing has nothing to say, and must not
        // replace a good file with an empty one.
        if (snapshot.levels().stream().allMatch(level -> level.endEpochMs() == 0)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                InsightsSnapshotCodec.write(out, snapshot);
            }
            move();
        } catch (IOException e) {
            if (!writeFailureLogged) {
                writeFailureLogged = true;
                log.warn("Peekaboot insights: cannot write {}; history will not survive this restart", file, e);
            }
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
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            InsightsSnapshotCodec.Header header = InsightsSnapshotCodec.readHeader(in);
            if (isTooOld(header)) {
                log.info("Peekaboot insights: {} is older than {}; starting with empty history", file, maxAge);
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

    private boolean isTooOld(InsightsSnapshotCodec.Header header) {
        return header.writtenAtEpochMs() < System.currentTimeMillis() - maxAge.toMillis();
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
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete the unusable insights snapshot {}", file, e);
        }
    }
}
