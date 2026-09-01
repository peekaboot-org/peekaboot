package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed-capacity ring of aggregated windows, stored as parallel primitive columns. */
public final class StatsRing {

    /** One column per statistic, keyed and ordered like {@link AggregateStats#byName()}. */
    private final Map<String, DoubleRing> stats = new LinkedHashMap<>();

    private final DoubleRing samples;

    public StatsRing(int capacity) {
        for (String name : AggregateStats.STAT_NAMES) {
            stats.put(name, new DoubleRing(capacity));
        }
        samples = new DoubleRing(capacity);
    }

    public synchronized void add(AggregateStats entry) {
        entry.byName().forEach((name, value) -> stats.get(name).add(value));
        samples.add(entry.samples());
    }

    public synchronized int size() {
        return samples.size();
    }

    /** Column arrays for the API - the internal samples column is deliberately excluded. */
    public synchronized Map<String, double[]> toArrays() {
        Map<String, double[]> columns = new LinkedHashMap<>();
        stats.forEach((name, ring) -> columns.put(name, ring.toArray()));
        return columns;
    }

    /**
     * Every column, including the {@code samples} column {@link #toArrays()} withholds
     * from the API - persistence needs it, because the next roll-up weights its average
     * by it. Order matches {@link InsightsSnapshot#STAT_COLUMNS}.
     */
    public synchronized double[][] toColumns() {
        double[][] columns = new double[InsightsSnapshot.STAT_COLUMNS.size()][];
        int i = 0;
        for (DoubleRing ring : stats.values()) {
            columns[i++] = ring.toArray();
        }
        columns[i] = samples.toArray();
        return columns;
    }

    /** Refills an empty ring from {@link #toColumns()} output, oldest entry first. */
    public synchronized void restore(double[][] columns) {
        int statCount = AggregateStats.STAT_NAMES.size();
        double[][] statColumns = Arrays.copyOf(columns, statCount);
        for (int i = 0; i < columns[0].length; i++) {
            add(entryAt(statColumns, columns[statCount], i));
        }
    }

    public synchronized List<AggregateStats> last(int n) {
        double[][] statColumns =
                stats.values().stream().map(ring -> ring.last(n)).toArray(double[][]::new);
        double[] counts = samples.last(n);
        List<AggregateStats> result = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            result.add(entryAt(statColumns, counts, i));
        }
        return result;
    }

    /** Row {@code i} of columns laid out in {@link AggregateStats#STAT_NAMES} order, plus its sample count. */
    private static AggregateStats entryAt(double[][] statColumns, double[] counts, int i) {
        return new AggregateStats(
                statColumns[0][i],
                statColumns[1][i],
                statColumns[2][i],
                statColumns[3][i],
                statColumns[4][i],
                statColumns[5][i],
                statColumns[6][i],
                (int) counts[i]);
    }
}
