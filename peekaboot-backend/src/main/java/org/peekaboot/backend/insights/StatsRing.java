package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed-capacity ring of aggregated windows, stored as parallel primitive columns. */
public final class StatsRing {

    private final DoubleRing min;
    private final DoubleRing max;
    private final DoubleRing avg;
    private final DoubleRing median;
    private final DoubleRing p90;
    private final DoubleRing p95;
    private final DoubleRing p99;
    private final DoubleRing samples;

    public StatsRing(int capacity) {
        min = new DoubleRing(capacity);
        max = new DoubleRing(capacity);
        avg = new DoubleRing(capacity);
        median = new DoubleRing(capacity);
        p90 = new DoubleRing(capacity);
        p95 = new DoubleRing(capacity);
        p99 = new DoubleRing(capacity);
        samples = new DoubleRing(capacity);
    }

    public synchronized void add(AggregateStats entry) {
        min.add(entry.min());
        max.add(entry.max());
        avg.add(entry.avg());
        median.add(entry.median());
        p90.add(entry.p90());
        p95.add(entry.p95());
        p99.add(entry.p99());
        samples.add(entry.samples());
    }

    public synchronized int size() {
        return avg.size();
    }

    /** Column arrays for the API - the internal samples column is deliberately excluded. */
    public synchronized Map<String, double[]> toArrays() {
        Map<String, double[]> columns = new LinkedHashMap<>();
        columns.put("min", min.toArray());
        columns.put("max", max.toArray());
        columns.put("avg", avg.toArray());
        columns.put("median", median.toArray());
        columns.put("p90", p90.toArray());
        columns.put("p95", p95.toArray());
        columns.put("p99", p99.toArray());
        return columns;
    }

    public synchronized List<AggregateStats> last(int n) {
        double[] mins = min.last(n);
        double[] maxes = max.last(n);
        double[] avgs = avg.last(n);
        double[] medians = median.last(n);
        double[] p90s = p90.last(n);
        double[] p95s = p95.last(n);
        double[] p99s = p99.last(n);
        double[] counts = samples.last(n);
        List<AggregateStats> result = new ArrayList<>(avgs.length);
        for (int i = 0; i < avgs.length; i++) {
            result.add(new AggregateStats(
                    mins[i], maxes[i], avgs[i], medians[i], p90s[i], p95s[i], p99s[i], (int) counts[i]));
        }
        return result;
    }
}
