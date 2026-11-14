package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A level above 0: the aggregated windows.
 *
 * @param statistics series id to each statistic's value per window, oldest first
 * @param samples series id to the level-0 samples behind each window, oldest first
 */
record WindowLevelData(
        int level,
        long intervalMs,
        long endEpochMs,
        int count,
        Map<String, Map<WindowStatistic, double[]>> statistics,
        Map<String, double[]> samples)
        implements LevelData {

    @Override
    public Set<String> seriesIds() {
        return statistics.keySet();
    }

    @Override
    public SeriesSummary summaryOf(String seriesId) {
        return SeriesSummary.of(
                AggregateStats.ofAggregates(windowsOf(seriesId)),
                LevelData.newest(column(seriesId, WindowStatistic.AVG)));
    }

    /** {@code seriesId}'s aggregated windows, oldest first. */
    private List<AggregateStats> windowsOf(String seriesId) {
        Map<WindowStatistic, double[]> columns = statistics.get(seriesId);
        double[] counts = samples.get(seriesId);
        List<AggregateStats> windows = new ArrayList<>(counts.length);
        for (int row = 0; row < counts.length; row++) {
            windows.add(AggregateStats.fromRow(columns, row, (int) counts[row]));
        }
        return windows;
    }

    @Override
    public double[] column(String seriesId, WindowStatistic statistic) {
        return statistics.get(seriesId).get(statistic);
    }

    @Override
    public InsightsDataResponse.SeriesData seriesData(String seriesId, boolean full) {
        return new InsightsDataResponse.SeriesData(
                summaryOf(seriesId), null, full ? boxedByWireName(statistics.get(seriesId)) : null);
    }

    private static Map<String, List<Double>> boxedByWireName(Map<WindowStatistic, double[]> columns) {
        Map<String, List<Double>> boxed = new LinkedHashMap<>();
        columns.forEach((statistic, values) -> boxed.put(statistic.wireName(), LevelData.boxed(values)));
        return boxed;
    }
}
