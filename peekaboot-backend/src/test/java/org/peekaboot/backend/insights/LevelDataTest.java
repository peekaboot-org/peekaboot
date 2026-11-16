package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.api.TraceView;

class LevelDataTest {

    /** Two one-minute windows of series {@code g}: six samples averaging 5, then two averaging 8. */
    private static LevelData twoWindows() {
        Map<WindowStatistic, double[]> statistics = new EnumMap<>(WindowStatistic.class);
        statistics.put(WindowStatistic.MIN, new double[] {1, 2});
        statistics.put(WindowStatistic.MAX, new double[] {9, 20});
        statistics.put(WindowStatistic.AVG, new double[] {5, 8});
        statistics.put(WindowStatistic.MEDIAN, new double[] {5, 8});
        statistics.put(WindowStatistic.P90, new double[] {9, 20});
        statistics.put(WindowStatistic.P95, new double[] {9, 20});
        statistics.put(WindowStatistic.P99, new double[] {9, 20});
        return new WindowLevelData(1, 60_000, 120_000, 2, Map.of("g", statistics), Map.of("g", new double[] {6, 2}));
    }

    /** Every statistic a ring holds: {@code given}, and two gaps for each one it leaves out. */
    private static Map<WindowStatistic, double[]> columns(Map<WindowStatistic, double[]> given) {
        Map<WindowStatistic, double[]> columns = new EnumMap<>(WindowStatistic.class);
        for (WindowStatistic statistic : WindowStatistic.values()) {
            columns.put(statistic, given.getOrDefault(statistic, new double[] {Double.NaN, Double.NaN}));
        }
        return columns;
    }

    /** JSON has no NaN, so every gap inside an aggregated column has to leave as null. */
    @Test
    void anAggregatedLevelMapsNaNToNullInsideEachStatColumn() {
        Map<WindowStatistic, double[]> given = Map.of(
                WindowStatistic.AVG, new double[] {5.0, Double.NaN},
                WindowStatistic.MAX, new double[] {Double.NaN, 9.0});
        LevelData data = new WindowLevelData(
                1, 60_000, 120_000, 2, Map.of("g", columns(given)), Map.of("g", new double[] {1, 0}));

        InsightsDataResponse response = data.toResponse(TraceView.FULL, Set.of("g"), List.of());

        InsightsDataResponse.SeriesData series = response.series().get("g");
        assertThat(series.values())
                .as("an aggregated level carries stats, not raw values")
                .isNull();
        assertThat(series.stats()).containsOnlyKeys("min", "max", "avg", "median", "p90", "p95", "p99");
        assertThat(series.stats().get("avg")).containsExactly(5.0, null);
        assertThat(series.stats().get("max")).containsExactly(null, 9.0);
        assertThat(response.level()).isEqualTo(1);
        assertThat(response.count()).isEqualTo(2);
    }

    /**
     * Above level 0 the median and the p95 are taken over the windows' own median and p95, nearest
     * rank with each window counted as often as it has samples. Of eight samples the fourth lies in
     * the first window and the eighth in the second, whose p95 holds its spike. Min and max stay the
     * exact extremes, and the average weighs each window by its samples.
     */
    @Test
    void anAggregatedLevelSummarisesFromItsWindows() {
        SeriesSummary summary = twoWindows().summaryOf("g");

        assertThat(summary).isEqualTo(new SeriesSummary(1.0, 5.75, 20.0, 5.0, 20.0, 8.0, 8));
    }

    @Test
    void theTimestampsEndAtTheNewestSampleOneIntervalApart() {
        assertThat(twoWindows().timestamps()).containsExactly(60_000L, 120_000L);
    }
}
