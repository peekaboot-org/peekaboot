package org.peekaboot.backend.insights;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.TraceView;

/**
 * Point-in-time copy of one level's ring contents. {@code tickValues} carries
 * raw per-tick values for level 0 (seriesId -&gt; values, oldest first);
 * {@code statValues} carries aggregated windows for levels &ge; 1
 * (seriesId -&gt; statName -&gt; values, oldest first), and {@code statSamples} the
 * level-0 samples behind each window (seriesId -&gt; counts, oldest first). Sample
 * {@code i} was taken {@code count - 1 - i} intervals before {@code endEpochMs}.
 */
record LevelData(
        int level,
        long intervalMs,
        long endEpochMs,
        int count,
        Map<String, double[]> tickValues,
        Map<String, Map<String, double[]>> statValues,
        Map<String, double[]> statSamples) {

    /** The series this level carries, in panel order. */
    Set<String> seriesIds() {
        return level == 0 ? tickValues.keySet() : statValues.keySet();
    }

    /**
     * The samples taken at or after {@code since}, every one where it is null. The newest sample
     * keeps its date, so the window stays dated from {@code endEpochMs}.
     */
    LevelData since(@Nullable Instant since) {
        if (since == null) {
            return this;
        }
        // compared as instants, since an ISO instant can lie past the epoch-millisecond range
        int kept = (int) timestamps().stream()
                .filter(at -> !Instant.ofEpochMilli(at).isBefore(since))
                .count();
        Map<String, Map<String, double[]>> keptStats = new LinkedHashMap<>();
        statValues.forEach((id, stats) -> keptStats.put(id, newest(stats, kept)));
        return new LevelData(
                level, intervalMs, endEpochMs, kept, newest(tickValues, kept), keptStats, newest(statSamples, kept));
    }

    private static Map<String, double[]> newest(Map<String, double[]> columns, int kept) {
        Map<String, double[]> newest = new LinkedHashMap<>();
        columns.forEach(
                (id, values) -> newest.put(id, Arrays.copyOfRange(values, values.length - kept, values.length)));
        return newest;
    }

    /** When each sample was taken, oldest first, in epoch milliseconds. */
    List<Long> timestamps() {
        List<Long> timestamps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            timestamps.add(endEpochMs - (count - 1 - i) * intervalMs);
        }
        return timestamps;
    }

    /** {@code seriesId}'s statistics over the window. */
    SeriesSummary summaryOf(String seriesId) {
        if (level == 0) {
            double[] values = tickValues.get(seriesId);
            return SeriesSummary.of(AggregateStats.of(values), newest(values));
        }
        List<AggregateStats> windows = windowsOf(seriesId);
        return SeriesSummary.of(
                AggregateStats.ofAggregates(windows),
                newest(windows.stream().mapToDouble(AggregateStats::avg).toArray()));
    }

    /** {@code seriesId}'s aggregated windows, oldest first; only for a level above 0. */
    List<AggregateStats> windowsOf(String seriesId) {
        Map<String, double[]> stats = statValues.get(seriesId);
        double[][] columns = AggregateStats.STAT_NAMES.stream().map(stats::get).toArray(double[][]::new);
        double[] samples = statSamples.get(seriesId);
        List<AggregateStats> windows = new ArrayList<>(samples.length);
        for (int row = 0; row < samples.length; row++) {
            windows.add(AggregateStats.fromRow(columns, row, (int) samples[row]));
        }
        return windows;
    }

    private static double newest(double[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            if (!Double.isNaN(values[i])) {
                return values[i];
            }
        }
        return Double.NaN;
    }

    /**
     * The API's view of the series {@code seriesIds} names, in panel order: each one's summary, and in
     * the full view its samples and their timestamps. The {@code double[]} columns are boxed to
     * {@code List<Double>} with NaN mapped to null, since Jackson would emit invalid JSON ({@code NaN})
     * otherwise.
     */
    InsightsDataResponse toResponse(TraceView view, Collection<String> seriesIds) {
        boolean full = view == TraceView.FULL;
        Map<String, InsightsDataResponse.SeriesData> series = new LinkedHashMap<>();
        for (String id : seriesIds()) {
            if (seriesIds.contains(id)) {
                series.put(
                        id,
                        new InsightsDataResponse.SeriesData(
                                summaryOf(id),
                                full && level == 0 ? boxed(tickValues.get(id)) : null,
                                full && level > 0 ? boxedStats(statValues.get(id)) : null));
            }
        }
        return new InsightsDataResponse(level, intervalMs, endEpochMs, count, full ? timestamps() : null, series);
    }

    private static Map<String, List<Double>> boxedStats(Map<String, double[]> stats) {
        Map<String, List<Double>> boxed = new LinkedHashMap<>();
        stats.forEach((name, values) -> boxed.put(name, boxed(values)));
        return boxed;
    }

    private static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(PeekabootJson.nanToNull(value));
        }
        return boxed;
    }
}
