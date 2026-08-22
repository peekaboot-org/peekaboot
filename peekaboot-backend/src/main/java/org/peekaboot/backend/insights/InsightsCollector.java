package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Samples every configured series into level 0 on each tick and rolls finer
 * levels up into coarser ones on request. Purely passive: methods are invoked
 * directly by the caller (tests now, the tick/roll-up threads in the next
 * task) - this class owns no threads of its own.
 */
public final class InsightsCollector {

    /** Notified after each tick and each roll-up. */
    public interface Listener {
        void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles);

        void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries);
    }

    private final long[] intervalMillis;
    private final Map<String, SeriesSampler> samplers = new LinkedHashMap<>();
    private final Map<String, DoubleRing> level0Rings = new LinkedHashMap<>();
    /** Per series id, one StatsRing per level; index 0 (level 0) is unused. */
    private final Map<String, StatsRing[]> statsRings = new LinkedHashMap<>();
    private final AtomicLongArray levelEndEpochMs;
    private final Map<String, TileState> tiles = new LinkedHashMap<>();
    private final Listener listener;

    public InsightsCollector(List<InsightsProperties.Level> levels, List<SeriesDef> series,
                              List<TileDef> tiles, MeterRegistry registry, Listener listener) {
        this.listener = listener;
        this.intervalMillis = new long[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            intervalMillis[i] = levels.get(i).getInterval().toMillis();
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

        for (TileDef def : tiles) {
            SeriesDef tileSeries = new SeriesDef(def.id(), def.label(), def.meter(), def.tags(), "value", null, null);
            boolean live = Boolean.TRUE.equals(def.live());
            this.tiles.put(def.id(), new TileState(new SeriesSampler(tileSeries, registry), live));
        }
    }

    /** Samples every series into level 0, samples tiles, and notifies the listener. */
    void tick(long epochMs) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, SeriesSampler> entry : samplers.entrySet()) {
            double value = entry.getValue().sample(intervalMillis[0]);
            level0Rings.get(entry.getKey()).add(value);
            values.put(entry.getKey(), value);
        }
        levelEndEpochMs.set(0, epochMs);

        for (TileState tile : tiles.values()) {
            tile.sample(intervalMillis[0]);
        }

        listener.onTick(epochMs, values, tileValues());
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
        double[] sampleCounts = window.stream().mapToDouble(AggregateStats::samples).toArray();
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

    /** Current tile values; NaN when a tile is not yet (or no longer) resolvable. */
    Map<String, Double> tileValues() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, TileState> entry : tiles.entrySet()) {
            values.put(entry.getKey(), entry.getValue().value);
        }
        return values;
    }

    /** seriesCount x (level0Size + sum of higher-level sizes x 7) x 8 bytes. */
    public static long estimateMemoryBytes(int seriesCount, List<InsightsProperties.Level> levels) {
        long doublesPerSeries = levels.get(0).getSize();
        for (int i = 1; i < levels.size(); i++) {
            doublesPerSeries += levels.get(i).getSize() * 7L;
        }
        return seriesCount * doublesPerSeries * 8L;
    }

    /** A tile's sampler plus its freeze state (static tiles stop sampling once resolved). */
    private static final class TileState {
        private final SeriesSampler sampler;
        private final boolean live;
        private volatile double value = Double.NaN;
        private boolean frozen;

        private TileState(SeriesSampler sampler, boolean live) {
            this.sampler = sampler;
            this.live = live;
        }

        private void sample(long intervalMillis) {
            if (live) {
                value = sampler.sample(intervalMillis);
                return;
            }
            if (frozen) {
                return;
            }
            double sampled = sampler.sample(intervalMillis);
            if (!Double.isNaN(sampled)) {
                value = sampled;
                frozen = true;
            }
        }
    }
}
