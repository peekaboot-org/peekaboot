package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.TraceView;

class LevelDataTest {

    /** Two one-minute windows of series {@code g}: six samples averaging 5, then two averaging 8. */
    private static LevelData twoWindows() {
        Map<String, double[]> stats = new LinkedHashMap<>();
        stats.put("min", new double[] {1, 2});
        stats.put("max", new double[] {9, 20});
        stats.put("avg", new double[] {5, 8});
        stats.put("median", new double[] {5, 8});
        stats.put("p90", new double[] {9, 20});
        stats.put("p95", new double[] {9, 20});
        stats.put("p99", new double[] {9, 20});
        return new LevelData(1, 60_000, 120_000, 2, Map.of(), Map.of("g", stats), Map.of("g", new double[] {6, 2}));
    }

    /** Every stat column a ring holds: {@code given}, and two gaps for each stat it leaves out. */
    private static Map<String, double[]> columns(Map<String, double[]> given) {
        Map<String, double[]> columns = new LinkedHashMap<>();
        AggregateStats.STAT_NAMES.forEach(name ->
                columns.put(name, given.getOrDefault(name, new double[] {Double.NaN, Double.NaN})));
        return columns;
    }

    /** JSON has no NaN, so every gap inside an aggregated column has to leave as null. */
    @Test
    void anAggregatedLevelMapsNaNToNullInsideEachStatColumn() {
        Map<String, double[]> given =
                Map.of("avg", new double[] {5.0, Double.NaN}, "max", new double[] {Double.NaN, 9.0});
        LevelData data = new LevelData(
                1, 60_000, 120_000, 2, Map.of(), Map.of("g", columns(given)), Map.of("g", new double[] {1, 0}));

        InsightsDataResponse response = data.toResponse(TraceView.FULL, Set.of("g"), List.of());

        InsightsDataResponse.SeriesData series = response.series().get("g");
        assertThat(series.values())
                .as("an aggregated level carries stats, not raw values")
                .isNull();
        assertThat(series.stats().get("avg")).containsExactly(5.0, null);
        assertThat(series.stats().get("max")).containsExactly(null, 9.0);
        assertThat(response.level()).isEqualTo(1);
        assertThat(response.count()).isEqualTo(2);
    }

    /**
     * Above level 0 the median and the p95 are taken over the windows' averages, nearest rank, so
     * a spike inside a window is averaged away; min and max stay the exact extremes, and the average
     * weighs each window by its samples.
     */
    @Test
    void anAggregatedLevelSummarisesFromItsWindows() {
        SeriesSummary summary = twoWindows().summaryOf("g");

        assertThat(summary).isEqualTo(new SeriesSummary(1.0, 5.75, 20.0, 5.0, 8.0, 8.0, 8));
    }

    @Test
    void sinceKeepsTheWindowsDatedAtOrAfterIt() {
        LevelData newest = twoWindows().since(Instant.ofEpochMilli(120_000));

        assertThat(newest.count()).isEqualTo(1);
        assertThat(newest.timestamps()).containsExactly(120_000L);
        assertThat(newest.summaryOf("g")).isEqualTo(new SeriesSummary(2.0, 8.0, 20.0, 8.0, 8.0, 8.0, 2));
    }

    /** An ISO instant can lie past the epoch-millisecond range; it still keeps no sample, or every one. */
    @Test
    void aSincePastTheMillisecondRangeKeepsNoneOrEvery() {
        assertThat(twoWindows().since(Instant.MAX).count()).isZero();
        assertThat(twoWindows().since(Instant.MIN).count()).isEqualTo(2);
    }

    @Test
    void theTimestampsEndAtTheNewestSampleOneIntervalApart() {
        assertThat(twoWindows().timestamps()).containsExactly(60_000L, 120_000L);
    }
}
