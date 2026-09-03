package org.peekaboot.backend.insights;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One aggregated window. Percentiles use nearest-rank over the ascending sort;
 * ofAggregates() computes percentiles over the finer entries' avg values -
 * a deliberate approximation (Micrometer keeps no raw samples).
 */
public record AggregateStats(
        double min, double max, double avg, double median, double p90, double p95, double p99, int samples) {

    public static final AggregateStats EMPTY =
            new AggregateStats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

    /** The statistic names, in {@link #byName()} order - the order the API, the SSE events and the file use. */
    public static final List<String> STAT_NAMES = List.copyOf(EMPTY.byName().keySet());

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

    public static AggregateStats ofAggregates(double[] mins, double[] maxes, double[] avgs, double[] sampleCounts) {
        double min = Double.NaN;
        double max = Double.NaN;
        double weightedSum = 0;
        int totalSamples = 0;
        int usable = 0;
        double[] usableAvgs = new double[avgs.length];
        for (int i = 0; i < avgs.length; i++) {
            if (Double.isNaN(avgs[i])) {
                continue;
            }
            usableAvgs[usable] = avgs[i];
            usable++;
            min = Double.isNaN(min) ? mins[i] : Math.min(min, mins[i]);
            max = Double.isNaN(max) ? maxes[i] : Math.max(max, maxes[i]);
            weightedSum += avgs[i] * sampleCounts[i];
            totalSamples += (int) sampleCounts[i];
        }
        if (usable == 0) {
            return EMPTY;
        }
        double[] sortedAvgs = Arrays.copyOf(usableAvgs, usable);
        Arrays.sort(sortedAvgs);
        double avg = totalSamples > 0 ? weightedSum / totalSamples : sortedAvgs[0];
        return new AggregateStats(
                min,
                max,
                avg,
                percentile(sortedAvgs, 0.50),
                percentile(sortedAvgs, 0.90),
                percentile(sortedAvgs, 0.95),
                percentile(sortedAvgs, 0.99),
                totalSamples);
    }

    /**
     * The seven statistics keyed by the name they travel under. {@code samples} is a count
     * the roll-up weights by, not a statistic anyone charts, and is deliberately absent.
     */
    public Map<String, Double> byName() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("min", min);
        stats.put("max", max);
        stats.put("avg", avg);
        stats.put("median", median);
        stats.put("p90", p90);
        stats.put("p95", p95);
        stats.put("p99", p99);
        return stats;
    }

    private static double percentile(double[] sorted, double quantile) {
        int rank = (int) Math.ceil(quantile * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
