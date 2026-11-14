package org.peekaboot.backend.insights;

import java.util.Map;
import java.util.Set;

/**
 * Level 0: the raw samples per tick.
 *
 * @param values series id to its samples, oldest first
 */
record TickLevelData(long intervalMs, long endEpochMs, int count, Map<String, double[]> values) implements LevelData {

    @Override
    public int level() {
        return 0;
    }

    @Override
    public Set<String> seriesIds() {
        return values.keySet();
    }

    @Override
    public SeriesSummary summaryOf(String seriesId) {
        double[] samples = values.get(seriesId);
        return SeriesSummary.of(AggregateStats.of(samples), LevelData.newest(samples));
    }

    @Override
    public double[] column(String seriesId, WindowStatistic statistic) {
        return values.get(seriesId);
    }

    @Override
    public InsightsDataResponse.SeriesData seriesData(String seriesId, boolean full) {
        return new InsightsDataResponse.SeriesData(
                summaryOf(seriesId), full ? LevelData.boxed(values.get(seriesId)) : null, null);
    }
}
