package org.peekaboot.backend.domain.insights;

import java.util.List;
import java.util.Map;

/**
 * One level's ring contents, ready for JSON: a gap in a ring is {@code null} here, since
 * JSON has no NaN.
 */
public record LevelDataResponse(
        int level, long intervalMs, long endEpochMs, int count, Map<String, SeriesData> series) {

    /**
     * {@code values} is populated for level 0 (raw ticks); {@code stats} for
     * levels &ge; 1 (stat name -&gt; values). Whichever doesn't apply is null.
     */
    public record SeriesData(List<Double> values, Map<String, List<Double>> stats) {}
}
