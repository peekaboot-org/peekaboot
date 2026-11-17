package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.insights.SeriesSampler.UnresolvedMeterReport;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples every configured series into level 0 of its {@link SeriesRings} on each tick and
 * rolls finer levels up into coarser ones on request. Owns one virtual thread per level
 * (see {@link #start()}) that drives {@link #tick(long)}/{@link #rollUp(int, long)}
 * on a boundary-aligned schedule; the sampling methods themselves remain
 * callable directly, which is how tests exercise them without starting threads.
 */
public final class InsightsCollector {

    private static final Logger log = LoggerFactory.getLogger(InsightsCollector.class);

    /** Notified after each tick and each roll-up. */
    public interface Listener {
        void onTick(long epochMs, Map<String, Double> values);

        void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries);
    }

    /**
     * The collector's read side of persistence. Awaited by the first level thread to
     * reach a write - never on the startup path, which is why loading a snapshot cannot
     * slow an application's boot.
     */
    interface SnapshotSource {

        /** The persisted rings, or empty if there are none or they did not arrive in time. */
        Optional<InsightsSnapshot> awaitSnapshot(Duration timeout);
    }

    private final Map<String, SeriesSampler> samplers = new LinkedHashMap<>();
    private final SeriesRings rings;

    private final TileTracker tiles;
    private final Listener listener;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private final SnapshotRestoreBarrier restoreBarrier;
    private final IntervalBoundary schedule;

    InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            Set<String> shippedSeriesIds,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener,
            SnapshotSource snapshotSource) {
        this(levels, series, shippedSeriesIds, tiles, registry, listener, snapshotSource, System::currentTimeMillis);
    }

    /**
     * {@code clock} supplies the epoch millis the level threads schedule their boundaries from
     * and a capture is dated with; tests fix it.
     */
    InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            Set<String> shippedSeriesIds,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener,
            SnapshotSource snapshotSource,
            LongSupplier clock) {
        this.listener = listener;
        this.restoreBarrier = new SnapshotRestoreBarrier(snapshotSource);
        this.schedule = new IntervalBoundary(clock, Thread::sleep);
        for (SeriesDef def : series) {
            UnresolvedMeterReport report =
                    shippedSeriesIds.contains(def.id()) ? UnresolvedMeterReport.DEBUG : UnresolvedMeterReport.WARN;
            samplers.put(def.id(), new SeriesSampler(def, registry, report));
        }
        this.rings = new SeriesRings(levels, samplers.keySet());

        this.tiles = new TileTracker(tiles, registry);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (int level = 0; level < rings.levelCount(); level++) {
            threads.add(startLevelThread(level));
        }
    }

    /**
     * Starts the thread of {@code level}. {@link #runLevel} catches a {@link RuntimeException}.
     * A thread that anything else ends logs it and is replaced, so the level keeps running.
     */
    private Thread startLevelThread(int level) {
        return Thread.ofVirtual()
                .name(threadName(level))
                .uncaughtExceptionHandler((thread, e) -> {
                    log.error(
                            "Peekaboot insights: the {} thread ended on an error; starting it again",
                            thread.getName(),
                            e);
                    // stop() holds the monitor while it joins this thread
                    if (running) {
                        replaceLevelThread(level, thread);
                    }
                })
                .start(() -> runLevel(level));
    }

    /**
     * Starts the level again unless a newer thread already runs it: a stop() and a start()
     * can get in before {@code dead} has the monitor.
     */
    private synchronized void replaceLevelThread(int level, Thread dead) {
        if (running && threads.get(level).equals(dead)) {
            threads.set(level, startLevelThread(level));
        }
    }

    /** Interrupts and joins every level thread (see {@link WorkerThreads}), then clears them. */
    public synchronized void stop() {
        running = false;
        WorkerThreads.stopAndJoin(threads);
        threads.clear();
    }

    public boolean isRunning() {
        return running;
    }

    private String threadName(int level) {
        return level == 0
                ? "peekaboot-insights-tick"
                : "peekaboot-insights-agg-" + IntervalFormat.humanize(Duration.ofMillis(rings.intervalMillis(level)));
    }

    /**
     * Sleeps to the next boundary of {@code level}'s interval (offset by half the finer
     * level's interval for aggregation levels, so its boundary write has landed), then runs
     * {@code tick}/{@code rollUp} and repeats until interrupted.
     */
    private void runLevel(int level) {
        long intervalMs = rings.intervalMillis(level);
        long offsetMs = level == 0 ? 0 : rings.intervalMillis(level - 1) / 2;
        schedule.runEvery(intervalMs, offsetMs, boundary -> {
            try {
                restoreBarrier.arriveBefore(this::restore);
                if (level == 0) {
                    tick(boundary);
                } else {
                    rollUp(level, boundary);
                }
            } catch (RuntimeException e) {
                log.warn("Insights {} failed for level {}", level == 0 ? "tick" : "roll-up", level, e);
            }
        });
    }

    /** Samples every series into level 0 and notifies the listener. */
    void tick(long epochMs) {
        long elapsedMs = elapsedSince(0, epochMs);
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, SeriesSampler> entry : samplers.entrySet()) {
            values.put(entry.getKey(), entry.getValue().sample(elapsedMs));
        }
        rings.appendTick(epochMs, values);

        listener.onTick(epochMs, values);
    }

    /**
     * Millis of meter activity this sample covers: the time since the level's last
     * stamped boundary, so a rate/delta derived across a gap is divided by the time
     * that really passed. Falls back to the nominal interval for the first sample
     * and for a backwards clock jump.
     */
    private long elapsedSince(int level, long epochMs) {
        long previous = rings.endEpochMs(level);
        return previous > 0 && epochMs > previous ? epochMs - previous : rings.intervalMillis(level);
    }

    /**
     * Aggregates the finer level's entries dated in the interval of {@code level} that ends at
     * {@code epochMs} into one {@link AggregateStats} per series, appends it to this level's
     * ring, and notifies the listener. Entries the finer level never wrote are left out, so a
     * window without any is a gap.
     */
    void rollUp(int level, long epochMs) {
        if (level < 1 || level >= rings.levelCount()) {
            throw new IllegalArgumentException("level must be in [1, " + (rings.levelCount() - 1) + "]");
        }
        int n = (int) (rings.intervalMillis(level) / rings.intervalMillis(level - 1));
        Map<String, AggregateStats> entries = new LinkedHashMap<>();
        if (level == 1) {
            rings.tickWindows(n, epochMs).forEach((id, window) -> entries.put(id, AggregateStats.of(window)));
        } else {
            rings.statsWindows(level - 1, n, epochMs)
                    .forEach((id, window) -> entries.put(id, AggregateStats.ofAggregates(window)));
        }
        rings.appendStats(level, epochMs, entries);

        listener.onRollUp(level, epochMs, entries);
    }

    /** The series the rings hold, so a load builds nothing for a series it cannot restore. */
    Set<String> seriesIds() {
        return Collections.unmodifiableSet(samplers.keySet());
    }

    /** One level's rings as the API reads them; see {@link SeriesRings#levelData(int, Instant)}. */
    LevelData levelData(int level, @Nullable Instant since) {
        return rings.levelData(level, since);
    }

    /** See {@link SeriesRings#captureAfter}; dated by the collector's clock. */
    InsightsSnapshot captureAfter(long[] persistedEndEpochMs) {
        return rings.captureAfter(schedule.now(), persistedEndEpochMs);
    }

    /** Applies persisted history to the rings, once, ahead of a level thread's first write. */
    void restore(InsightsSnapshot snapshot) {
        rings.restore(snapshot);
    }

    /**
     * Whether the persisted history has reached the rings. Until it has, what the rings hold
     * is only what this run sampled itself, which is less than the file it would replace.
     */
    boolean hasRestoredHistory() {
        return restoreBarrier.hasApplied();
    }

    /** Current tile values, sampled by this read (see {@link TileTracker}); NaN when a tile is not resolvable. */
    Map<String, Double> tileValues() {
        return tiles.values();
    }
}
