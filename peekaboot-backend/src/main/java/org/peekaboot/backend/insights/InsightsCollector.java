package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
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
    public interface SnapshotSource {

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

    public InsightsCollector(
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
        List<String> names = threadNames();
        for (int level = 0; level < rings.levelCount(); level++) {
            int boundLevel = level;
            Thread thread = Thread.ofVirtual().name(names.get(level)).unstarted(() -> runLevel(boundLevel));
            threads.add(thread);
            thread.start();
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

    /** Names the level threads would run under, without starting anything. */
    List<String> threadNames() {
        List<String> names = new ArrayList<>(rings.levelCount());
        names.add("peekaboot-insights-tick");
        for (int level = 1; level < rings.levelCount(); level++) {
            names.add("peekaboot-insights-agg-"
                    + IntervalFormat.humanize(Duration.ofMillis(rings.intervalMillis(level))));
        }
        return names;
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
     * Aggregates the last {@code levels[level].interval / levels[level-1].interval}
     * entries of the finer level into one {@link AggregateStats} per series, appends
     * it to this level's ring, and notifies the listener.
     */
    void rollUp(int level, long epochMs) {
        if (level < 1 || level >= rings.levelCount()) {
            throw new IllegalArgumentException("level must be in [1, " + (rings.levelCount() - 1) + "]");
        }
        int n = (int) (rings.intervalMillis(level) / rings.intervalMillis(level - 1));
        Map<String, AggregateStats> entries = new LinkedHashMap<>();
        if (level == 1) {
            rings.tickWindows(n).forEach((id, window) -> entries.put(id, AggregateStats.of(window)));
        } else {
            rings.statsWindows(level - 1, n)
                    .forEach((id, window) -> entries.put(id, AggregateStats.ofAggregates(window)));
        }
        rings.appendStats(level, epochMs, entries);

        listener.onRollUp(level, epochMs, entries);
    }

    /** One level's rings as the API reads them; see {@link SeriesRings#levelData(int)}. */
    LevelData levelData(int level) {
        return rings.levelData(level);
    }

    /** The rings as persistence writes them, dated by the collector's clock; see {@link SeriesRings#capture(long)}. */
    InsightsSnapshot capture() {
        return rings.capture(schedule.now());
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
