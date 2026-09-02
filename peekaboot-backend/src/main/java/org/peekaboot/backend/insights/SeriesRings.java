package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;
import org.peekaboot.backend.insights.config.InsightsProperties;

/**
 * One collector's ring buffers: per series a level-0 ring of raw samples and one
 * {@link StatsRing} per coarser level, plus the boundary each level was last stamped with.
 * The same set is what persistence writes and reads back.
 *
 * <p>Written by one collector thread per level and read without a common lock by the API
 * and the snapshot writer. Every ring synchronizes on itself, so a reader sees whole
 * samples; the skew a concurrent write leaves is what {@link #snapshot(int)} and
 * {@link #capture()} describe. A level's samples are appended before
 * {@link #stamp(int, long)} dates them, so a reader that has already read the boundary can
 * find the rings one sample ahead of it, never one behind.
 */
final class SeriesRings {

    private final long[] intervalMillis;
    private final int[] levelSizes;
    private final Map<String, DoubleRing> level0Rings = new LinkedHashMap<>();
    /** Per series id, one StatsRing per level; index 0 (level 0) is unused. */
    private final Map<String, StatsRing[]> statsRings = new LinkedHashMap<>();

    private final AtomicLongArray levelEndEpochMs;

    SeriesRings(List<InsightsProperties.Level> levels, Collection<String> seriesIds) {
        this.intervalMillis = new long[levels.size()];
        this.levelSizes = new int[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            intervalMillis[i] = levels.get(i).getInterval().toMillis();
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

    /** The configured series, in the order every snapshot and capture carries them. */
    Set<String> seriesIds() {
        return level0Rings.keySet();
    }

    /** Appends one sampled value to a series' level-0 ring. */
    void addTick(String seriesId, double value) {
        level0Rings.get(seriesId).add(value);
    }

    /** Appends one aggregated window to a series' ring at {@code level}. */
    void addStats(String seriesId, int level, AggregateStats stats) {
        statsRings.get(seriesId)[level].add(stats);
    }

    /** The last {@code n} level-0 samples of a series, oldest first. */
    double[] tickWindow(String seriesId, int n) {
        return level0Rings.get(seriesId).last(n);
    }

    /** The last {@code n} aggregated windows of a series at {@code level}, oldest first. */
    List<AggregateStats> statsWindow(String seriesId, int level, int n) {
        return statsRings.get(seriesId)[level].last(n);
    }

    /** The boundary this level's newest sample is dated with; 0 before the level's first write. */
    long endEpochMs(int level) {
        return levelEndEpochMs.get(level);
    }

    /** Dates a level's newest samples, once every series' write for {@code epochMs} has landed. */
    void stamp(int level, long epochMs) {
        levelEndEpochMs.set(level, epochMs);
    }

    /**
     * Appends one empty entry per boundary this level slept through (a suspended
     * laptop, a stalled sampler). Timestamps derive from {@code (endEpoch, index)}, so a
     * silently skipped boundary would shift every older sample one interval into the
     * future; the gap is therefore recorded as NaN entries. Capped at the ring size,
     * beyond which every visible sample is a gap anyway. Listeners see no synthetic
     * events - clients mirror the same geometry from the event's epoch.
     */
    void fillMissed(int level, long epochMs) {
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
     * Point-in-time copy of one level's ring contents, read without a lock like every API
     * read. {@code count} is the length of whichever series the loop lands on last: a tick
     * or roll-up landing mid-read can leave the series read after it one sample longer
     * than those before, and the response tolerates that skew rather than trimming the way
     * {@link #capture()} must for the file format.
     */
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

    /**
     * Everything persistence needs: every level of every series, including the
     * {@code samples} column the API's {@link #snapshot(int)} deliberately omits.
     * Ring contents and {@code endEpochMs} are read without a common lock, exactly as
     * the API reads them, so a tick or roll-up landing mid-capture can leave the series
     * read after it one sample longer than those read before. The snapshot is made
     * self-consistent instead: every level's count is what all of its series have, and
     * a series that is longer loses its newest sample - the tick that produced it is
     * not yet complete, and the next capture carries it. What remains is that the newest
     * sample can be dated one interval early, once, in a cache.
     */
    InsightsSnapshot capture() {
        long[] endEpochMs = new long[intervalMillis.length];
        for (int level = 0; level < intervalMillis.length; level++) {
            endEpochMs[level] = levelEndEpochMs.get(level);
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
        int[] counts = sharedCounts(series);
        for (List<double[][]> byLevel : series.values()) {
            for (int level = 0; level < intervalMillis.length; level++) {
                byLevel.set(level, trimmed(byLevel.get(level), counts[level]));
            }
        }
        List<InsightsSnapshot.Level> levels = new ArrayList<>(intervalMillis.length);
        for (int level = 0; level < intervalMillis.length; level++) {
            levels.add(new InsightsSnapshot.Level(
                    intervalMillis[level], levelSizes[level], endEpochMs[level], counts[level]));
        }
        return new InsightsSnapshot(System.currentTimeMillis(), levels, series);
    }

    /** Per level, the sample count every series has; 0 when no series is configured at all. */
    private int[] sharedCounts(Map<String, List<double[][]>> series) {
        int[] counts = new int[intervalMillis.length];
        if (series.isEmpty()) {
            return counts;
        }
        Arrays.fill(counts, Integer.MAX_VALUE);
        for (List<double[][]> byLevel : series.values()) {
            for (int level = 0; level < intervalMillis.length; level++) {
                counts[level] = Math.min(counts[level], byLevel.get(level)[0].length);
            }
        }
        return counts;
    }

    /** Columns are oldest first, so cutting at {@code count} drops the newest samples. */
    private static double[][] trimmed(double[][] columns, int count) {
        if (columns[0].length == count) {
            return columns;
        }
        double[][] cut = new double[columns.length][];
        for (int i = 0; i < columns.length; i++) {
            cut[i] = Arrays.copyOf(columns[i], count);
        }
        return cut;
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

    /** seriesCount x (level0Size + sum of higher-level sizes x 8) x 8 bytes. */
    static long estimateMemoryBytes(int seriesCount, List<InsightsProperties.Level> levels) {
        long doublesPerSeries = levels.get(0).getSize();
        for (int i = 1; i < levels.size(); i++) {
            doublesPerSeries += levels.get(i).getSize() * 8L;
        }
        return seriesCount * doublesPerSeries * 8L;
    }
}
