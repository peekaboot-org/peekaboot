package org.peekaboot.backend.domain.insights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.insights.LevelSnapshot;

/**
 * One level's ring contents, ready for JSON. {@code double[]} from
 * {@link LevelSnapshot} is boxed to {@code List<Double>} with NaN mapped to
 * {@code null} — Jackson would emit invalid JSON ({@code NaN}) otherwise.
 */
public record LevelDataResponse(
        int level, long intervalMs, long endEpochMs, int count, Map<String, SeriesData> series) {

    /**
     * {@code values} is populated for level 0 (raw ticks); {@code stats} for
     * levels &ge; 1 (stat name -&gt; values). Whichever doesn't apply is null.
     */
    public record SeriesData(List<Double> values, Map<String, List<Double>> stats) {}

    public static LevelDataResponse from(LevelSnapshot snapshot) {
        Map<String, SeriesData> series = new LinkedHashMap<>();
        if (snapshot.level() == 0) {
            for (Map.Entry<String, double[]> entry : snapshot.tickValues().entrySet()) {
                series.put(entry.getKey(), new SeriesData(boxed(entry.getValue()), null));
            }
        } else {
            for (Map.Entry<String, Map<String, double[]>> entry :
                    snapshot.statValues().entrySet()) {
                Map<String, List<Double>> stats = new LinkedHashMap<>();
                for (Map.Entry<String, double[]> statEntry : entry.getValue().entrySet()) {
                    stats.put(statEntry.getKey(), boxed(statEntry.getValue()));
                }
                series.put(entry.getKey(), new SeriesData(null, stats));
            }
        }
        return new LevelDataResponse(
                snapshot.level(), snapshot.intervalMs(), snapshot.endEpochMs(), snapshot.count(), series);
    }

    private static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(Double.isNaN(value) ? null : value);
        }
        return boxed;
    }
}
