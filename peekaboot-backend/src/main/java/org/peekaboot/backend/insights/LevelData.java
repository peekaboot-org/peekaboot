package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.TraceView;

/**
 * Point-in-time copy of one level's ring contents: {@link TickLevelData} for level 0's samples,
 * {@link WindowLevelData} for the aggregated windows above it. Sample {@code i} was taken
 * {@code count - 1 - i} intervals before {@code endEpochMs}.
 */
sealed interface LevelData permits TickLevelData, WindowLevelData {

    int level();

    long intervalMs();

    long endEpochMs();

    int count();

    /** The series this level carries, in panel order. */
    Set<String> seriesIds();

    /**
     * {@code seriesId}'s statistics over the window. Above level 0 the median and the p95 are taken
     * over the windows' own median and p95, each window weighted by its samples.
     */
    SeriesSummary summaryOf(String seriesId);

    /** {@code seriesId}'s value per sample: the sample itself at level 0, the window's {@code statistic} above it. */
    double[] column(String seriesId, WindowStatistic statistic);

    /** {@code seriesId}'s summary, and in the {@code full} view its samples at level 0 or its statistics above it. */
    InsightsDataResponse.SeriesData seriesData(String seriesId, boolean full);

    /** When each sample was taken, oldest first, in epoch milliseconds. */
    default List<Long> timestamps() {
        List<Long> timestamps = new ArrayList<>(count());
        for (int i = 0; i < count(); i++) {
            timestamps.add(endEpochMs() - (count() - 1 - i) * intervalMs());
        }
        return timestamps;
    }

    /**
     * The API's view of the series {@code seriesIds} names, in panel order: each one's summary, and in
     * the full view its samples and their timestamps, beside the window's {@code problems}.
     */
    default InsightsDataResponse toResponse(
            TraceView view, Collection<String> seriesIds, List<InsightsProblem> problems) {
        boolean full = view == TraceView.FULL;
        Map<String, InsightsDataResponse.SeriesData> series = new LinkedHashMap<>();
        for (String id : seriesIds()) {
            if (seriesIds.contains(id)) {
                series.put(id, seriesData(id, full));
            }
        }
        return new InsightsDataResponse(
                level(), intervalMs(), endEpochMs(), count(), full ? timestamps() : null, series, problems);
    }

    /** The newest of {@code values} that is no gap; NaN where every one is. */
    static double newest(double[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            if (!Double.isNaN(values[i])) {
                return values[i];
            }
        }
        return Double.NaN;
    }

    /** {@code values} with NaN and the infinities mapped to null, since JSON has neither. */
    static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(PeekabootJson.finiteOrNull(value));
        }
        return boxed;
    }
}
