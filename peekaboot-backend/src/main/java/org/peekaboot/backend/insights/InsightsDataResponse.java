package org.peekaboot.backend.insights;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One level's history over the requested window, ready for JSON: a gap is {@code null} here,
 * since JSON has no NaN.
 *
 * @param level the level the summaries were computed on
 * @param endEpochMs when the newest sample was taken; 0 before the level's first one
 * @param count the samples in the window
 * @param timestamps when each sample was taken, oldest first, in epoch milliseconds and shared by every series; null
 *     in the summary view
 */
public record InsightsDataResponse(
        int level,
        long intervalMs,
        long endEpochMs,
        int count,
        @Nullable List<Long> timestamps,
        Map<String, SeriesData> series) {

    /**
     * One series. {@code values} carries level 0's samples and {@code stats} a higher level's
     * aggregates (stat name to values), each in the full view only; the other is null.
     */
    public record SeriesData(
            SeriesSummary summary,
            @Nullable List<Double> values,
            @Nullable Map<String, List<Double>> stats) {}
}
