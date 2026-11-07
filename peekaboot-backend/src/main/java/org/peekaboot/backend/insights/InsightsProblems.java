package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.peekaboot.backend.insights.config.InsightsProperties;

/**
 * Checks a level's window against the {@link ProblemRule}s. Every rule reads the window the
 * request selected, so a level above 0 judges the windows' averages where it takes a median or a
 * percentile, and the exact extremes where it takes a maximum or a minimum.
 */
final class InsightsProblems {

    private static final double PERCENT = 100.0;
    private static final int QUARTERS = 4;

    private final InsightsProperties.Problems thresholds;
    private final long slowHttpMs;
    private final long tickMs;

    /**
     * @param slowHttpMs the slow-trace threshold, which {@link ProblemRule#SLOW_HTTP} shares with the traces
     * @param tickMs level 0's interval, the time one rate sample covers
     */
    InsightsProblems(InsightsProperties.Problems thresholds, long slowHttpMs, long tickMs) {
        this.thresholds = thresholds;
        this.slowHttpMs = slowHttpMs;
        this.tickMs = tickMs;
    }

    /** What {@code data}'s window reached, in rule order. */
    List<InsightsProblem> of(LevelData data) {
        List<InsightsProblem> problems = new ArrayList<>();
        check(problems, ProblemRule.CPU_HIGH, summary(data, SeriesSummary::median), thresholds.getCpuHighPercent());
        check(
                problems,
                ProblemRule.GC_OVERHEAD,
                summary(data, SeriesSummary::median),
                thresholds.getGcOverheadPercent());
        check(problems, ProblemRule.HEAP_NEAR_MAX, heapShare(data), thresholds.getHeapNearMaxPercent());
        check(problems, ProblemRule.THREAD_GROWTH, threadGrowth(data), thresholds.getThreadGrowth());
        check(problems, ProblemRule.POOL_WAITING, summary(data, SeriesSummary::max), thresholds.getPoolWaiting());
        check(problems, ProblemRule.SERVER_ERRORS, serverErrorShare(data), thresholds.getServerErrorsPercent());
        check(problems, ProblemRule.ERROR_LOGS, errorLogLines(data), thresholds.getErrorLogs());
        check(problems, ProblemRule.SLOW_HTTP, summary(data, SeriesSummary::p95), (double) slowHttpMs);
        return problems;
    }

    private static void check(List<InsightsProblem> problems, ProblemRule rule, Measure measure, double threshold) {
        OptionalDouble value = measure.of(rule);
        if (value.isPresent() && value.getAsDouble() >= threshold) {
            problems.add(new InsightsProblem(
                    rule,
                    rule.severity(),
                    rule.series(),
                    value.getAsDouble(),
                    threshold,
                    rule.unit(),
                    rule.sentence().formatted(number(value.getAsDouble()), number(threshold))));
        }
    }

    /** What a rule measures over its series; empty where one of them is absent or holds no sample. */
    @FunctionalInterface
    private interface Measure {
        OptionalDouble of(ProblemRule rule);
    }

    /** One statistic of the rule's first series. */
    private static Measure summary(LevelData data, Function<SeriesSummary, Double> statistic) {
        return rule -> {
            String id = rule.series().getFirst();
            return data.seriesIds().contains(id)
                    ? present(statistic.apply(data.summaryOf(id)))
                    : OptionalDouble.empty();
        };
    }

    /**
     * The median of used over maximum, per sample; a sample without a positive maximum has none, as
     * the JVM reports an undefined one as -1.
     */
    private static Measure heapShare(LevelData data) {
        return rule -> {
            if (!carriesAll(data, rule)) {
                return OptionalDouble.empty();
            }
            double[] used = data.column(rule.series().get(0), "avg");
            double[] max = data.column(rule.series().get(1), "avg");
            double[] shares = IntStream.range(0, used.length)
                    .filter(i -> max[i] > 0)
                    .mapToDouble(i -> used[i] / max[i] * PERCENT)
                    .toArray();
            return present(AggregateStats.of(shares).median());
        };
    }

    /**
     * How far the fewest live threads of the window's last quarter rose above those of its first; a
     * window of fewer than four samples has no quarters.
     */
    private static Measure threadGrowth(LevelData data) {
        return rule -> {
            if (!carriesAll(data, rule)) {
                return OptionalDouble.empty();
            }
            double[] minima = data.column(rule.series().getFirst(), "min");
            int quarter = minima.length / QUARTERS;
            if (quarter == 0) {
                return OptionalDouble.empty();
            }
            double first =
                    AggregateStats.of(Arrays.copyOfRange(minima, 0, quarter)).min();
            double last = AggregateStats.of(Arrays.copyOfRange(minima, minima.length - quarter, minima.length))
                    .min();
            return present(last - first);
        };
    }

    /**
     * Server errors over requests across the window; the two rates are summed over the same samples,
     * so their seconds cancel.
     */
    private static Measure serverErrorShare(LevelData data) {
        return rule -> {
            if (!carriesAll(data, rule)) {
                return OptionalDouble.empty();
            }
            double errors = total(data.summaryOf(rule.series().get(0)));
            double requests = total(data.summaryOf(rule.series().get(1)));
            return requests > 0 ? present(errors / requests * PERCENT) : OptionalDouble.empty();
        };
    }

    /** The ERROR lines the per-second rate adds up to over the window, each sample covering one tick. */
    private Measure errorLogLines(LevelData data) {
        return rule -> {
            String id = rule.series().getFirst();
            if (!data.seriesIds().contains(id)) {
                return OptionalDouble.empty();
            }
            double lines = total(data.summaryOf(id)) * tickMs / 1000.0;
            if (Double.isNaN(lines)) {
                return OptionalDouble.empty();
            }
            // a tick that ran late spreads a line over more than one interval; a line still counts as one
            return OptionalDouble.of(lines > 0 ? Math.max(1.0, (double) Math.round(lines)) : 0.0);
        };
    }

    /** The sum of a series' level-0 samples: their average times their number. NaN where there are none. */
    private static double total(SeriesSummary summary) {
        Double avg = summary.avg();
        return avg == null ? Double.NaN : avg * summary.samples();
    }

    private static boolean carriesAll(LevelData data, ProblemRule rule) {
        return data.seriesIds().containsAll(rule.series());
    }

    private static OptionalDouble present(Double value) {
        return value == null || Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** A whole number without decimals, anything else to one decimal. */
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
