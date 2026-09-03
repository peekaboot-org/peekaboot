package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.insights.LevelDataResponse;

/**
 * Point-in-time copy of one level's ring contents. {@code tickValues} carries
 * raw per-tick values for level 0 (seriesId -&gt; values, oldest first);
 * {@code statValues} carries aggregated windows for levels &ge; 1
 * (seriesId -&gt; statName -&gt; values, oldest first).
 */
public record LevelSnapshot(
        int level,
        long intervalMs,
        long endEpochMs,
        int count,
        Map<String, double[]> tickValues,
        Map<String, Map<String, double[]>> statValues) {

    /**
     * The API's view: the {@code double[]} columns boxed to {@code List<Double>} with NaN
     * mapped to null - Jackson would emit invalid JSON ({@code NaN}) otherwise.
     */
    public LevelDataResponse toResponse() {
        Map<String, LevelDataResponse.SeriesData> series = new LinkedHashMap<>();
        if (level == 0) {
            for (Map.Entry<String, double[]> entry : tickValues.entrySet()) {
                series.put(entry.getKey(), new LevelDataResponse.SeriesData(boxed(entry.getValue()), null));
            }
        } else {
            for (Map.Entry<String, Map<String, double[]>> entry : statValues.entrySet()) {
                Map<String, List<Double>> stats = new LinkedHashMap<>();
                for (Map.Entry<String, double[]> statEntry : entry.getValue().entrySet()) {
                    stats.put(statEntry.getKey(), boxed(statEntry.getValue()));
                }
                series.put(entry.getKey(), new LevelDataResponse.SeriesData(null, stats));
            }
        }
        return new LevelDataResponse(level, intervalMs, endEpochMs, count, series);
    }

    private static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(PeekabootJson.nanToNull(value));
        }
        return boxed;
    }
}
