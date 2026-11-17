package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-capacity ring of aggregated windows, stored as parallel primitive columns. Not
 * thread-safe: {@link SeriesRings} reaches every ring under its own lock.
 */
final class StatsRing {

    /** One column per statistic, in {@link WindowStatistic} order. */
    private final Map<WindowStatistic, DoubleRing> stats = new EnumMap<>(WindowStatistic.class);

    private final DoubleRing samples;

    StatsRing(int capacity) {
        for (WindowStatistic statistic : WindowStatistic.values()) {
            stats.put(statistic, new DoubleRing(capacity));
        }
        samples = new DoubleRing(capacity);
    }

    void add(AggregateStats entry) {
        stats.forEach((statistic, ring) -> ring.add(statistic.of(entry)));
        samples.add(entry.samples());
    }

    int size() {
        return samples.size();
    }

    /** Each statistic's column over the newest {@code n} windows, oldest first, without the samples column. */
    Map<WindowStatistic, double[]> statistics(int n) {
        Map<WindowStatistic, double[]> columns = new EnumMap<>(WindowStatistic.class);
        stats.forEach((statistic, ring) -> columns.put(statistic, ring.last(n)));
        return columns;
    }

    /** The level-0 samples behind each of the newest {@code n} windows, oldest first. */
    double[] samples(int n) {
        return samples.last(n);
    }

    /**
     * Every column over the newest {@code n} windows, including the {@code samples} column
     * {@link #statistics(int)} leaves out - persistence needs it, because the next roll-up
     * weights its average by it. Order matches {@link InsightsSnapshot#STAT_COLUMNS}.
     */
    double[][] lastColumns(int n) {
        double[][] columns = new double[InsightsSnapshot.STAT_COLUMNS.size()][];
        int column = 0;
        for (DoubleRing ring : stats.values()) {
            columns[column] = ring.last(n);
            column++;
        }
        columns[column] = samples.last(n);
        return columns;
    }

    /** Refills an empty ring from {@link #lastColumns} output, oldest entry first. */
    void restore(double[][] columns) {
        Map<WindowStatistic, double[]> statColumns = new EnumMap<>(WindowStatistic.class);
        int column = 0;
        for (WindowStatistic statistic : stats.keySet()) {
            statColumns.put(statistic, columns[column]);
            column++;
        }
        double[] counts = columns[column];
        for (int i = 0; i < counts.length; i++) {
            add(AggregateStats.fromRow(statColumns, i, (int) counts[i]));
        }
    }

    List<AggregateStats> last(int n) {
        Map<WindowStatistic, double[]> statColumns = statistics(n);
        double[] counts = samples(n);
        List<AggregateStats> result = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            result.add(AggregateStats.fromRow(statColumns, i, (int) counts[i]));
        }
        return result;
    }
}
