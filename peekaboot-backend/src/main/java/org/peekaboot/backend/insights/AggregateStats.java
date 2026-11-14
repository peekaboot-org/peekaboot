package org.peekaboot.backend.insights;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * One aggregated window, its percentiles by nearest rank. {@link #of} ranks the samples.
 * {@link #ofAggregates} ranks the finer windows' value of the same name, each counted as often as
 * the window has samples, since a window keeps none to rank.
 */
public record AggregateStats(
        double min, double max, double avg, double median, double p90, double p95, double p99, int samples) {

    public static final AggregateStats EMPTY =
            new AggregateStats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

    /** The statistics' wire names, in {@link WindowStatistic} order. */
    public static final List<String> STAT_NAMES = Arrays.stream(WindowStatistic.values())
            .map(WindowStatistic::wireName)
            .toList();

    public static AggregateStats of(double[] values) {
        double[] usable = Arrays.stream(values).filter(v -> !Double.isNaN(v)).toArray();
        if (usable.length == 0) {
            return EMPTY;
        }
        Arrays.sort(usable);
        double sum = Arrays.stream(usable).sum();
        return new AggregateStats(
                usable[0],
                usable[usable.length - 1],
                sum / usable.length,
                percentile(usable, 0.50),
                percentile(usable, 0.90),
                percentile(usable, 0.95),
                percentile(usable, 0.99),
                usable.length);
    }

    public static AggregateStats ofAggregates(List<AggregateStats> window) {
        double min = Double.NaN;
        double max = Double.NaN;
        double leastAvg = Double.NaN;
        double weightedSum = 0;
        int totalSamples = 0;
        for (AggregateStats entry : window) {
            if (Double.isNaN(entry.avg)) {
                continue;
            }
            min = Double.isNaN(min) ? entry.min : Math.min(min, entry.min);
            max = Double.isNaN(max) ? entry.max : Math.max(max, entry.max);
            leastAvg = Double.isNaN(leastAvg) ? entry.avg : Math.min(leastAvg, entry.avg);
            weightedSum += entry.avg * entry.samples;
            totalSamples += entry.samples;
        }
        if (Double.isNaN(leastAvg)) {
            return EMPTY;
        }
        double avg = totalSamples > 0 ? weightedSum / totalSamples : leastAvg;
        return new AggregateStats(
                min,
                max,
                avg,
                weightedPercentile(window, AggregateStats::median, 0.50),
                weightedPercentile(window, AggregateStats::p90, 0.90),
                weightedPercentile(window, AggregateStats::p95, 0.95),
                weightedPercentile(window, AggregateStats::p99, 0.99),
                totalSamples);
    }

    /**
     * The seven statistics keyed by the name they travel under. {@code samples} is a count
     * the roll-up weights by, not a statistic anyone charts, and is deliberately absent.
     */
    public Map<String, Double> byName() {
        Map<String, Double> stats = new LinkedHashMap<>();
        for (WindowStatistic statistic : WindowStatistic.values()) {
            stats.put(statistic.wireName(), statistic.of(this));
        }
        return stats;
    }

    /** The window in row {@code row} of {@code columns}, which holds one column per statistic. */
    static AggregateStats fromRow(Map<WindowStatistic, double[]> columns, int row, int samples) {
        return new AggregateStats(
                columns.get(WindowStatistic.MIN)[row],
                columns.get(WindowStatistic.MAX)[row],
                columns.get(WindowStatistic.AVG)[row],
                columns.get(WindowStatistic.MEDIAN)[row],
                columns.get(WindowStatistic.P90)[row],
                columns.get(WindowStatistic.P95)[row],
                columns.get(WindowStatistic.P99)[row],
                samples);
    }

    private static double percentile(double[] sorted, double quantile) {
        int rank = (int) Math.ceil(quantile * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    /** Nearest rank over the windows' {@code statistic}, each counted once per sample; NaN where none has both. */
    private static double weightedPercentile(
            List<AggregateStats> window, ToDoubleFunction<AggregateStats> statistic, double quantile) {
        List<AggregateStats> ranked = window.stream()
                .filter(entry -> entry.samples > 0 && !Double.isNaN(statistic.applyAsDouble(entry)))
                .sorted(Comparator.comparingDouble(statistic))
                .toList();
        int rank = (int) Math.ceil(
                quantile * ranked.stream().mapToInt(AggregateStats::samples).sum());
        int reached = 0;
        for (AggregateStats entry : ranked) {
            reached += entry.samples;
            if (reached >= rank) {
                return statistic.applyAsDouble(entry);
            }
        }
        return Double.NaN;
    }
}
