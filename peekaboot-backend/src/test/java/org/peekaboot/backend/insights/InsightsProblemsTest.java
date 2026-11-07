package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.Unit;

/** Each rule at its default threshold, just below it, and without the series it reads. */
class InsightsProblemsTest {

    private static final long TICK_MS = 10_000;
    private static final long SLOW_HTTP_MS = 1_000;

    private final InsightsProblems problems =
            new InsightsProblems(new InsightsProperties.Problems(), SLOW_HTTP_MS, TICK_MS);

    /** Level 0 holding {@code series}, each sample one tick apart. */
    private static LevelData ticks(Map<String, double[]> series) {
        int count =
                series.values().stream().mapToInt(values -> values.length).max().orElse(0);
        return new LevelData(0, TICK_MS, count * TICK_MS, count, series, Map.of(), Map.of());
    }

    private static Map<String, double[]> series(String id, double... values) {
        Map<String, double[]> series = new LinkedHashMap<>();
        series.put(id, values);
        return series;
    }

    private List<ProblemRule> rulesIn(LevelData data) {
        return problems.of(data).stream().map(InsightsProblem::rule).toList();
    }

    /** Twenty latencies: eighteen of 100 ms, then {@code p95}, then an outlier of 5 s that only a maximum would see. */
    private static double[] latencies(double p95) {
        double[] latencies = new double[20];
        Arrays.fill(latencies, 100);
        latencies[18] = p95;
        latencies[19] = 5_000;
        return latencies;
    }

    @Test
    void aCpuMedianAtTheThresholdIsNamedWithItsValueAndThreshold() {
        List<InsightsProblem> found = problems.of(ticks(series("cpu.process", 70, 80, 90)));

        assertThat(found)
                .containsExactly(new InsightsProblem(
                        ProblemRule.CPU_HIGH,
                        IssueSeverity.WARNING,
                        List.of("cpu.process"),
                        80,
                        80,
                        Unit.PERCENT,
                        "Process CPU ran at a median of 80 percent, at or above 80."));
    }

    @Test
    void aCpuMedianJustBelowTheThresholdIsNotNamed() {
        assertThat(rulesIn(ticks(series("cpu.process", 70, 79.9, 90)))).isEmpty();
    }

    /** A disabled panel's series is absent, an unresolved one holds only gaps; neither is judged healthy or failing. */
    @Test
    void aRuleWhoseSeriesIsAbsentOrHoldsNoSampleIsSkipped() {
        assertThat(rulesIn(ticks(series("heap.used", 1, 2)))).isEmpty();
        assertThat(rulesIn(ticks(series("cpu.process", Double.NaN, Double.NaN))))
                .isEmpty();
        assertThat(rulesIn(ticks(series("log-events.errors", Double.NaN)))).isEmpty();
    }

    @Test
    void aGcOverheadMedianAtTheThresholdIsNamed() {
        assertThat(rulesIn(ticks(series("cpu.gc-overhead", 10)))).containsExactly(ProblemRule.GC_OVERHEAD);
        assertThat(rulesIn(ticks(series("cpu.gc-overhead", 9.9)))).isEmpty();
    }

    @Test
    void theHeapShareIsTheMedianOfUsedOverMaximumPerSample() {
        Map<String, double[]> heap = series("heap.used", 95, 85, 92);
        heap.put("heap.max", new double[] {100, 100, 100});

        List<InsightsProblem> found = problems.of(ticks(heap));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo(ProblemRule.HEAP_NEAR_MAX);
            assertThat(problem.series()).containsExactly("heap.used", "heap.max");
            assertThat(problem.value()).isEqualTo(92.0);
        });
    }

    /** The JVM reports a heap without a configured maximum as -1; a share of no positive maximum means nothing. */
    @Test
    void aHeapWithoutAPositiveMaximumIsSkipped() {
        Map<String, double[]> heap = series("heap.used", 95, 95, 95);
        heap.put("heap.max", new double[] {-1, 0, 0});

        assertThat(rulesIn(ticks(heap))).isEmpty();
    }

    @Test
    void threadGrowthComparesTheFewestThreadsOfTheLastQuarterWithTheFirst() {
        List<InsightsProblem> found = problems.of(ticks(series("threads.live", 100, 90, 95, 99, 150, 160, 140, 145)));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo(ProblemRule.THREAD_GROWTH);
            assertThat(problem.value()).isEqualTo(50.0);
            assertThat(problem.message())
                    .isEqualTo("The fewest live threads grew by 50 from the window's first quarter to its last, at or"
                            + " above 50.");
        });
        assertThat(rulesIn(ticks(series("threads.live", 100, 90, 95, 99, 150, 160, 139, 145))))
                .isEmpty();
    }

    @Test
    void aWindowOfFewerThanFourSamplesHasNoQuartersToCompare() {
        assertThat(rulesIn(ticks(series("threads.live", 10, 100, 200)))).isEmpty();
    }

    @Test
    void oneConnectionWaitingForThePoolIsAnError() {
        List<InsightsProblem> found = problems.of(ticks(series("db-pool.pending", 0, 1, 0)));

        assertThat(found).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo(ProblemRule.POOL_WAITING);
            assertThat(problem.severity()).isEqualTo(IssueSeverity.ERROR);
            assertThat(problem.value()).isEqualTo(1.0);
        });
    }

    @Test
    void theServerErrorShareIsTheirRateOverTheRequestRate() {
        Map<String, double[]> http = series("http-throughput.server-errors", 0, 1);
        http.put("http-throughput.requests", new double[] {10, 10});

        assertThat(problems.of(ticks(http))).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo(ProblemRule.SERVER_ERRORS);
            assertThat(problem.value()).isEqualTo(5.0);
            assertThat(problem.unit()).isEqualTo(Unit.PERCENT);
        });
    }

    @Test
    void aWindowWithoutRequestsHasNoServerErrorShare() {
        Map<String, double[]> http = series("http-throughput.server-errors", 0, 0);
        http.put("http-throughput.requests", new double[] {0, 0});

        assertThat(rulesIn(ticks(http))).isEmpty();
    }

    /** A rate of 0.1 a second over a 10 s tick is one line. */
    @Test
    void oneErrorLineInTheWindowIsNamed() {
        assertThat(problems.of(ticks(series("log-events.errors", 0, 0.1))))
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.rule()).isEqualTo(ProblemRule.ERROR_LOGS);
                    assertThat(problem.value()).isEqualTo(1.0);
                    assertThat(problem.message()).isEqualTo("Lines logged at ERROR numbered 1, at or above 1.");
                });
        assertThat(rulesIn(ticks(series("log-events.errors", 0, 0)))).isEmpty();
    }

    /** A tick that ran three intervals late spreads one line thin; it still counts as one. */
    @Test
    void aLineSeenByALateTickStillCountsAsOne() {
        assertThat(problems.of(ticks(series("log-events.errors", 0, 0.03))))
                .singleElement()
                .satisfies(problem -> assertThat(problem.value()).isEqualTo(1.0));
    }

    /** Rates of 0.2 and 0.3 a second over two 10 s ticks are five lines; the gap between them adds none. */
    @Test
    void theErrorLinesAreTheRatesSummedOverEveryTick() {
        assertThat(problems.of(ticks(series("log-events.errors", 0.2, Double.NaN, 0.3))))
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.value()).isEqualTo(5.0);
                    assertThat(problem.message()).isEqualTo("Lines logged at ERROR numbered 5, at or above 1.");
                });
    }

    /** The 95th percentile of twenty samples is the second highest, so the one outlier is not what is judged. */
    @Test
    void theAverageLatencysP95AtTheSlowTraceThresholdIsNamed() {
        assertThat(problems.of(ticks(series("http-latency.avg", latencies(1200)))))
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.rule()).isEqualTo(ProblemRule.SLOW_HTTP);
                    assertThat(problem.value()).isEqualTo(1200.0);
                    assertThat(problem.threshold()).isEqualTo(1000.0);
                    assertThat(problem.unit()).isEqualTo(Unit.MILLIS);
                });
        assertThat(rulesIn(ticks(series("http-latency.avg", latencies(999))))).isEmpty();
    }

    /** Above level 0 each window's average rate covers its samples, each one tick long. */
    @Test
    void anAggregatedLevelCountsTheErrorLinesEveryWindowCovers() {
        Map<String, double[]> stats = new LinkedHashMap<>();
        AggregateStats.STAT_NAMES.forEach(name -> stats.put(name, new double[] {0.05}));
        LevelData window = new LevelData(
                1,
                60_000,
                60_000,
                1,
                Map.of(),
                Map.of("log-events.errors", stats),
                Map.of("log-events.errors", new double[] {6}));

        assertThat(problems.of(window))
                .singleElement()
                .satisfies(problem -> assertThat(problem.value()).isEqualTo(3.0));
    }

    @Test
    void problemsAreListedInRuleOrder() {
        Map<String, double[]> window = series("log-events.errors", 1);
        window.put("cpu.process", new double[] {95});
        window.put("db-pool.pending", new double[] {2});

        assertThat(rulesIn(ticks(window)))
                .containsExactly(ProblemRule.CPU_HIGH, ProblemRule.POOL_WAITING, ProblemRule.ERROR_LOGS);
    }
}
