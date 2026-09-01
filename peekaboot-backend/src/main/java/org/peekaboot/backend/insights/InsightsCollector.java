package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLongArray;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Samples every configured series into level 0 on each tick and rolls finer
 * levels up into coarser ones on request. Owns one virtual thread per level
 * (see {@link #start()}) that drives {@link #tick(long)}/{@link #rollUp(int, long)}
 * on a boundary-aligned schedule; the sampling methods themselves remain
 * callable directly, which is how tests exercise them without starting threads.
 */
public final class InsightsCollector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsCollector.class);

    /** Notified after each tick and each roll-up. */
    public interface Listener {
        Listener NO_OP = new Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
                // NO_OP: collectors without an interested listener discard events
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                // NO_OP: collectors without an interested listener discard events
            }
        };

        void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles);

        void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries);
    }

    /**
     * The collector's read side of persistence. Awaited by the first level thread to
     * reach a write - never on the startup path, which is why loading a snapshot cannot
     * slow an application's boot.
     */
    public interface SnapshotSource {
        SnapshotSource NONE = timeout -> Optional.empty();

        /** The persisted rings, or empty if there are none or they did not arrive in time. */
        Optional<InsightsSnapshot> awaitSnapshot(Duration timeout);
    }

    private final long[] intervalMillis;
    private final int[] levelSizes;
    private final Map<String, SeriesSampler> samplers = new LinkedHashMap<>();
    private final Map<String, DoubleRing> level0Rings = new LinkedHashMap<>();
    /** Per series id, one StatsRing per level; index 0 (level 0) is unused. */
    private final Map<String, StatsRing[]> statsRings = new LinkedHashMap<>();

    private final AtomicLongArray levelEndEpochMs;
    private final TileTracker tiles;
    private final Listener listener;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private final SnapshotRestoreBarrier restoreBarrier;

    public InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener) {
        this(levels, series, tiles, registry, listener, SnapshotSource.NONE);
    }

    public InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener,
            SnapshotSource snapshotSource) {
        this.listener = listener;
        this.restoreBarrier = new SnapshotRestoreBarrier(snapshotSource);
        this.intervalMillis = new long[levels.size()];
        this.levelSizes = new int[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            intervalMillis[i] = levels.get(i).getInterval().toMillis();
            levelSizes[i] = levels.get(i).getSize();
        }
        this.levelEndEpochMs = new AtomicLongArray(levels.size());

        for (SeriesDef def : series) {
            samplers.put(def.id(), new SeriesSampler(def, registry));
            level0Rings.put(def.id(), new DoubleRing(levels.get(0).getSize()));
            StatsRing[] rings = new StatsRing[levels.size()];
            for (int level = 1; level < levels.size(); level++) {
                rings[level] = new StatsRing(levels.get(level).getSize());
            }
            statsRings.put(def.id(), rings);
        }

        this.tiles = new TileTracker(tiles, registry);
    }

    /** Starts one virtual thread per level, each ticking/rolling up on its own boundary-aligned schedule. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        List<String> names = threadNames();
        for (int level = 0; level < intervalMillis.length; level++) {
            int boundLevel = level;
            Thread thread = Thread.ofVirtual().name(names.get(level)).unstarted(() -> runLevel(boundLevel));
            threads.add(thread);
            thread.start();
        }
    }

    /** Interrupts and joins every level thread (up to 2s each), then clears them. */
    @Override
    public synchronized void stop() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Names the level threads would run under, without starting anything. */
    List<String> threadNames() {
        List<String> names = new ArrayList<>(intervalMillis.length);
        names.add("peekaboot-insights-tick");
        for (int level = 1; level < intervalMillis.length; level++) {
            names.add("peekaboot-insights-agg-" + formatInterval(Duration.ofMillis(intervalMillis[level])));
        }
        return names;
    }

    /** Renders a duration compactly: whole hours as "Nh", whole minutes as "Nm", else "Ns"/"Nms". */
    static String formatInterval(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 3_600_000 == 0) {
            return (millis / 3_600_000) + "h";
        }
        if (millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }

    /**
     * Sleeps to the next boundary of {@code level}'s interval (offset by half the finer
     * level's interval for aggregation levels, so its boundary write has landed), then runs
     * {@code tick}/{@code rollUp} and repeats until interrupted.
     */
    private void runLevel(int level) {
        long intervalMs = intervalMillis[level];
        long offsetMs = level == 0 ? 0 : intervalMillis[level - 1] / 2;
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            long boundary = ((now / intervalMs) + 1) * intervalMs;
            try {
                Thread.sleep(boundary + offsetMs - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
        }
    }

    /** Samples every series into level 0, samples tiles, and notifies the listener. */
    void tick(long epochMs) {
        long elapsedMs = elapsedSince(0, epochMs);
        fillMissed(0, epochMs);

        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, SeriesSampler> entry : samplers.entrySet()) {
            double value = entry.getValue().sample(elapsedMs);
            level0Rings.get(entry.getKey()).add(value);
            values.put(entry.getKey(), value);
        }
        levelEndEpochMs.set(0, epochMs);

        tiles.sample(elapsedMs);

        listener.onTick(epochMs, values, tileValues());
    }

    /**
     * Millis of meter activity this sample covers: the time since the level's last
     * stamped boundary, so a rate/delta derived across a gap is divided by the time
     * that really passed. Falls back to the nominal interval for the first sample
     * and for a backwards clock jump.
     */
    private long elapsedSince(int level, long epochMs) {
        long previous = levelEndEpochMs.get(level);
        return previous > 0 && epochMs > previous ? epochMs - previous : intervalMillis[level];
    }

    /**
     * Appends one empty entry per boundary this level slept through (a suspended
     * laptop, a stalled sampler). Timestamps derive from {@code (endEpoch, index)},
     * so a silently skipped boundary would shift every older sample one interval
     * into the future; the spec calls for NaN gaps instead. Capped at the ring size,
     * beyond which every visible sample is a gap anyway. Listeners see no synthetic
     * events - clients mirror the same geometry from the event's epoch.
     */
    private void fillMissed(int level, long epochMs) {
        long previous = levelEndEpochMs.get(level);
        if (previous <= 0 || epochMs <= previous) {
            return;
        }
        long missed = (epochMs - previous) / intervalMillis[level] - 1;
        int count = (int) Math.min(Math.max(missed, 0), levelSizes[level]);
        if (level == 0) {
            for (DoubleRing ring : level0Rings.values()) {
                for (int i = 0; i < count; i++) {
                    ring.add(Double.NaN);
                }
            }
        } else {
            for (StatsRing[] rings : statsRings.values()) {
                for (int i = 0; i < count; i++) {
                    rings[level].add(AggregateStats.EMPTY);
                }
            }
        }
    }

    /**
     * Aggregates the last {@code levels[level].interval / levels[level-1].interval}
     * entries of the finer level into one {@link AggregateStats} per series, appends
     * it to this level's ring, and notifies the listener.
     */
    void rollUp(int level, long epochMs) {
        if (level < 1 || level >= intervalMillis.length) {
            throw new IllegalArgumentException("level must be in [1, " + (intervalMillis.length - 1) + "]");
        }
        fillMissed(level, epochMs);

        int n = (int) (intervalMillis[level] / intervalMillis[level - 1]);
        Map<String, AggregateStats> entries = new LinkedHashMap<>();
        for (Map.Entry<String, StatsRing[]> entry : statsRings.entrySet()) {
            String id = entry.getKey();
            AggregateStats stats = level == 1
                    ? AggregateStats.of(level0Rings.get(id).last(n))
                    : aggregateFromFinerLevel(entry.getValue()[level - 1], n);
            entry.getValue()[level].add(stats);
            entries.put(id, stats);
        }
        levelEndEpochMs.set(level, epochMs);

        listener.onRollUp(level, epochMs, entries);
    }

    private static AggregateStats aggregateFromFinerLevel(StatsRing finerRing, int n) {
        List<AggregateStats> window = finerRing.last(n);
        double[] mins = window.stream().mapToDouble(AggregateStats::min).toArray();
        double[] maxes = window.stream().mapToDouble(AggregateStats::max).toArray();
        double[] avgs = window.stream().mapToDouble(AggregateStats::avg).toArray();
        double[] sampleCounts =
                window.stream().mapToDouble(AggregateStats::samples).toArray();
        return AggregateStats.ofAggregates(mins, maxes, avgs, sampleCounts);
    }

    /** Point-in-time copy of one level's ring contents. */
    LevelSnapshot snapshot(int level) {
        long intervalMs = intervalMillis[level];
        long endEpochMs = levelEndEpochMs.get(level);
        if (level == 0) {
            Map<String, double[]> tickValues = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<String, DoubleRing> entry : level0Rings.entrySet()) {
                double[] values = entry.getValue().toArray();
                tickValues.put(entry.getKey(), values);
                count = values.length;
            }
            return new LevelSnapshot(0, intervalMs, endEpochMs, count, tickValues, Map.of());
        }
        Map<String, Map<String, double[]>> statValues = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, StatsRing[]> entry : statsRings.entrySet()) {
            StatsRing ring = entry.getValue()[level];
            statValues.put(entry.getKey(), ring.toArrays());
            count = ring.size();
        }
        return new LevelSnapshot(level, intervalMs, endEpochMs, count, Map.of(), statValues);
    }

    int levelCount() {
        return intervalMillis.length;
    }

    /**
     * Everything persistence needs: every level of every series, including the
     * {@code samples} column the API's {@link #snapshot(int)} deliberately omits.
     * Ring contents and {@code endEpochMs} are read without a common lock, exactly as
     * the API reads them - a tick landing mid-capture can date the newest sample one
     * interval early, once, in a cache.
     */
    InsightsSnapshot capture() {
        List<InsightsSnapshot.Level> levels = new ArrayList<>(intervalMillis.length);
        for (int level = 0; level < intervalMillis.length; level++) {
            levels.add(new InsightsSnapshot.Level(
                    intervalMillis[level], levelSizes[level], levelEndEpochMs.get(level), filled(level)));
        }
        Map<String, List<double[][]>> series = new LinkedHashMap<>();
        for (Map.Entry<String, DoubleRing> entry : level0Rings.entrySet()) {
            List<double[][]> byLevel = new ArrayList<>(intervalMillis.length);
            byLevel.add(new double[][] {entry.getValue().toArray()});
            StatsRing[] rings = statsRings.get(entry.getKey());
            for (int level = 1; level < intervalMillis.length; level++) {
                byLevel.add(rings[level].toColumns());
            }
            series.put(entry.getKey(), byLevel);
        }
        return new InsightsSnapshot(System.currentTimeMillis(), levels, series);
    }

    /**
     * Refills the rings from a snapshot whose geometry the store has already matched
     * against the live config. Series the config no longer declares are skipped, and
     * a series the file never carried is filled with gaps instead. Restoring each level's
     * {@code endEpochMs} is what makes the outage visible: {@link #fillMissed} pads the
     * gap on this level's first tick or roll-up, with no separate replay to maintain.
     */
    void restore(InsightsSnapshot snapshot) {
        for (Map.Entry<String, DoubleRing> entry : level0Rings.entrySet()) {
            List<double[][]> persisted = snapshot.series().get(entry.getKey());
            StatsRing[] rings = statsRings.get(entry.getKey());
            if (persisted != null && persisted.size() == intervalMillis.length) {
                entry.getValue().restore(persisted.get(0)[0]);
                for (int level = 1; level < intervalMillis.length; level++) {
                    rings[level].restore(persisted.get(level));
                }
            } else {
                padWithGaps(entry.getValue(), rings, snapshot.levels());
            }
        }
        for (int level = 0; level < intervalMillis.length; level++) {
            levelEndEpochMs.set(level, snapshot.levels().get(level).endEpochMs());
        }
    }

    /**
     * Brings a series the snapshot did not supply up to the length the snapshot's other
     * series have. Every level carries one sample count for all series at once - both in
     * {@link #capture()} and in the file - so a series left short would have the next
     * capture promise more values in its header than it writes. Gaps are also what the
     * window deserves: the series was not being sampled for it, and a gap is exactly what
     * the charts already draw for that.
     */
    private void padWithGaps(DoubleRing level0, StatsRing[] rings, List<InsightsSnapshot.Level> levels) {
        for (int i = 0; i < levels.get(0).count(); i++) {
            level0.add(Double.NaN);
        }
        for (int level = 1; level < intervalMillis.length; level++) {
            for (int i = 0; i < levels.get(level).count(); i++) {
                rings[level].add(AggregateStats.EMPTY);
            }
        }
    }

    /**
     * Whether the persisted history has reached the rings. Until it has, what the rings hold
     * is only what this run sampled itself, which is less than the file it would replace.
     */
    boolean hasRestoredHistory() {
        return restoreBarrier.hasApplied();
    }

    /** How much of a level's ring is filled; 0 when no series is configured at all. */
    private int filled(int level) {
        if (level == 0) {
            return level0Rings.isEmpty()
                    ? 0
                    : level0Rings.values().iterator().next().size();
        }
        return statsRings.isEmpty()
                ? 0
                : statsRings.values().iterator().next()[level].size();
    }

    /** Current tile values; NaN when a tile is not yet (or no longer) resolvable. */
    Map<String, Double> tileValues() {
        return tiles.values();
    }

    /** seriesCount x (level0Size + sum of higher-level sizes x 7) x 8 bytes. */
    public static long estimateMemoryBytes(int seriesCount, List<InsightsProperties.Level> levels) {
        long doublesPerSeries = levels.get(0).getSize();
        for (int i = 1; i < levels.size(); i++) {
            doublesPerSeries += levels.get(i).getSize() * 7L;
        }
        return seriesCount * doublesPerSeries * 8L;
    }
}
