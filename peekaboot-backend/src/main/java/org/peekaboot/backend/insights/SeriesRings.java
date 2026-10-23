package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import org.peekaboot.backend.insights.config.InsightsProperties;

/**
 * One collector's ring buffers: per series a level-0 ring of raw samples and one
 * {@link StatsRing} per coarser level, plus the boundary each level was last stamped with.
 * The same set is what persistence writes and reads back.
 *
 * <p>Written by one collector thread per level, and once by {@link #restore} ahead of their
 * first write; read by the API and the snapshot writer. A tick or roll-up lands as one write
 * under one lock: its gap fill, every series' sample and the stamp that dates them. A restore
 * holds the same lock, and {@link #snapshot(int)}, {@link #capture()} and the roll-up window
 * reads run under it, so a reader sees whole ticks only. Meters are sampled before the write, so
 * the lock covers in-memory appends and nothing slower.
 */
final class SeriesRings {

    private final long[] intervalMillis;
    private final int[] levelSizes;
    private final Map<String, DoubleRing> level0Rings = new LinkedHashMap<>();
    /** Per series id, one StatsRing per level; index 0 (level 0) is unused. */
    private final Map<String, StatsRing[]> statsRings = new LinkedHashMap<>();

    private final AtomicLongArray levelEndEpochMs;

    private final Object lock = new Object();

    SeriesRings(List<InsightsProperties.Level> levels, Collection<String> seriesIds) {
        this.intervalMillis = new long[levels.size()];
        this.levelSizes = new int[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            intervalMillis[i] = levels.get(i).intervalMillis();
            levelSizes[i] = levels.get(i).getSize();
        }
        this.levelEndEpochMs = new AtomicLongArray(levels.size());

        for (String id : seriesIds) {
            level0Rings.put(id, new DoubleRing(levelSizes[0]));
            StatsRing[] rings = new StatsRing[levels.size()];
            for (int level = 1; level < levels.size(); level++) {
                rings[level] = new StatsRing(levelSizes[level]);
            }
            statsRings.put(id, rings);
        }
    }

    int levelCount() {
        return intervalMillis.length;
    }

    /** The interval {@code level} is sampled or aggregated at. */
    long intervalMillis(int level) {
        return intervalMillis[level];
    }

    /**
     * Appends one sample to every series' level-0 ring and dates them {@code epochMs}, as one
     * write. A series {@code values} lacks gets a gap, and ids no series carries are ignored,
     * so every ring grows by the same count.
     */
    void appendTick(long epochMs, Map<String, Double> values) {
        synchronized (lock) {
            fillMissed(0, epochMs);
            for (Map.Entry<String, DoubleRing> entry : level0Rings.entrySet()) {
                entry.getValue().add(values.getOrDefault(entry.getKey(), Double.NaN));
            }
            levelEndEpochMs.set(0, epochMs);
        }
    }

    /**
     * Appends one aggregated window to every series' ring at {@code level} and dates them
     * {@code epochMs}, as one write, with the same gap and unknown-id rules as
     * {@link #appendTick}.
     */
    void appendStats(int level, long epochMs, Map<String, AggregateStats> entries) {
        synchronized (lock) {
            fillMissed(level, epochMs);
            for (Map.Entry<String, StatsRing[]> entry : statsRings.entrySet()) {
                entry.getValue()[level].add(entries.getOrDefault(entry.getKey(), AggregateStats.EMPTY));
            }
            levelEndEpochMs.set(level, epochMs);
        }
    }

    /**
     * Per series, its last {@code n} level-0 samples, oldest first. Read under the same lock
     * as {@link #appendTick}, so every window ends at the same tick.
     */
    Map<String, double[]> tickWindows(int n) {
        synchronized (lock) {
            Map<String, double[]> windows = new LinkedHashMap<>();
            level0Rings.forEach((id, ring) -> windows.put(id, ring.last(n)));
            return windows;
        }
    }

    /**
     * Per series, its last {@code n} aggregated windows at {@code level}, oldest first. Read
     * under the same lock as {@link #appendStats}, so every window ends at the same roll-up.
     */
    Map<String, List<AggregateStats>> statsWindows(int level, int n) {
        synchronized (lock) {
            Map<String, List<AggregateStats>> windows = new LinkedHashMap<>();
            statsRings.forEach((id, rings) -> windows.put(id, rings[level].last(n)));
            return windows;
        }
    }

    /** The boundary this level's newest sample is dated with; 0 before the level's first write. */
    long endEpochMs(int level) {
        return levelEndEpochMs.get(level);
    }

    /**
     * Appends one empty entry per boundary this level slept through (a suspended
     * laptop, a stalled sampler). Timestamps derive from {@code (endEpoch, index)}, so a
     * silently skipped boundary would shift every older sample one interval into the
     * future; the gap is therefore recorded as NaN entries. Capped at the ring size,
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
     * Point-in-time copy of one level's ring contents, read under the same lock as
     * {@link #appendTick} so it holds whole ticks only: every series carries {@code count}
     * samples and {@code endEpochMs} dates the newest. The chart dates each sample from those
     * two, so a partial tick would misdate every sample and repeat the next live event.
     */
    LevelSnapshot snapshot(int level) {
        synchronized (lock) {
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
    }

    /**
     * Everything persistence needs: every level of every series, including the
     * {@code samples} column the API's {@link #snapshot(int)} deliberately omits. Read under
     * the same lock as {@link #appendTick}, like {@link #snapshot(int)}, so every level holds
     * whole ticks only, one sample count for every series, and its {@code endEpochMs} dates
     * the newest of them.
     */
    InsightsSnapshot capture() {
        synchronized (lock) {
            long[] endEpochMs = new long[intervalMillis.length];
            for (int level = 0; level < intervalMillis.length; level++) {
                endEpochMs[level] = levelEndEpochMs.get(level);
            }
            Map<String, List<double[][]>> series = new LinkedHashMap<>();
            int[] counts = new int[intervalMillis.length];
            for (Map.Entry<String, DoubleRing> entry : level0Rings.entrySet()) {
                List<double[][]> byLevel = new ArrayList<>(intervalMillis.length);
                double[][] level0 = {entry.getValue().toArray()};
                byLevel.add(level0);
                counts[0] = level0[0].length;
                StatsRing[] rings = statsRings.get(entry.getKey());
                for (int level = 1; level < intervalMillis.length; level++) {
                    byLevel.add(rings[level].toColumns());
                    counts[level] = rings[level].size();
                }
                series.put(entry.getKey(), byLevel);
            }
            List<InsightsSnapshot.Level> levels = new ArrayList<>(intervalMillis.length);
            for (int level = 0; level < intervalMillis.length; level++) {
                levels.add(new InsightsSnapshot.Level(
                        intervalMillis[level], levelSizes[level], endEpochMs[level], counts[level]));
            }
            return new InsightsSnapshot(System.currentTimeMillis(), levels, series);
        }
    }

    /**
     * Refills the rings from a snapshot whose geometry the store has already matched
     * against the live config. Series the config no longer declares are skipped, and
     * a series the file never carried is filled with gaps instead. Restoring each level's
     * {@code endEpochMs} is what makes the outage visible: {@link #fillMissed} pads the
     * gap on this level's first tick or roll-up, with no separate replay to maintain.
     */
    void restore(InsightsSnapshot snapshot) {
        synchronized (lock) {
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
     * The doubles the rings hold, at 8 bytes each: level 0 keeps one per sample, every
     * coarser level one per sample per {@link InsightsSnapshot#STAT_COLUMNS stat column}.
     */
    static long estimateMemoryBytes(int seriesCount, List<InsightsProperties.Level> levels) {
        long doublesPerSeries = levels.get(0).getSize();
        for (int i = 1; i < levels.size(); i++) {
            doublesPerSeries += (long) levels.get(i).getSize() * InsightsSnapshot.STAT_COLUMNS.size();
        }
        return seriesCount * doublesPerSeries * Double.BYTES;
    }
}
