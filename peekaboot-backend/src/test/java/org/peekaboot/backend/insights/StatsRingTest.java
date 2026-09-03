package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatsRingTest {

    private static AggregateStats entry(double value) {
        return AggregateStats.of(new double[] {value});
    }

    @Test
    void storesEntriesAndExposesColumns() {
        StatsRing ring = new StatsRing(3);
        ring.add(entry(1));
        ring.add(entry(2));
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.toArrays().get("avg")).containsExactly(1.0, 2.0);
        assertThat(ring.toArrays().get("min")).containsExactly(1.0, 2.0);
        assertThat(ring.toArrays()).containsOnlyKeys("min", "max", "avg", "median", "p90", "p95", "p99");
    }

    @Test
    void overwritesOldest() {
        StatsRing ring = new StatsRing(2);
        ring.add(entry(1));
        ring.add(entry(2));
        ring.add(entry(3));
        assertThat(ring.toArrays().get("avg")).containsExactly(2.0, 3.0);
    }

    @Test
    void lastReturnsNewestEntries() {
        StatsRing ring = new StatsRing(5);
        ring.add(entry(1));
        ring.add(entry(2));
        ring.add(entry(3));
        assertThat(ring.last(2)).extracting(AggregateStats::avg).containsExactly(2.0, 3.0);
    }

    @Test
    void toColumnsExposesAllEightColumnsInStatColumnsOrder() {
        // every field distinct, so a column swapped with its neighbour cannot pass by accident
        StatsRing ring = new StatsRing(3);
        ring.add(new AggregateStats(1, 2, 3, 4, 5, 6, 7, 42));
        double[][] columns = ring.toColumns();
        assertThat(columns.length).isEqualTo(8);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("min")]).containsExactly(1.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("max")]).containsExactly(2.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("avg")]).containsExactly(3.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("median")]).containsExactly(4.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("p90")]).containsExactly(5.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("p95")]).containsExactly(6.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("p99")]).containsExactly(7.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("samples")]).containsExactly(42.0);
    }

    @Test
    void restoreRefillsAnEmptyRingFromToColumnsOutput() {
        // every field distinct, so restore() reading a column into the wrong field would fail here
        AggregateStats original = new AggregateStats(1, 2, 3, 4, 5, 6, 7, 42);
        StatsRing source = new StatsRing(3);
        source.add(original);

        StatsRing restored = new StatsRing(3);
        restored.restore(source.toColumns());

        assertThat(restored.size()).isEqualTo(1);
        assertThat(restored.last(1)).containsExactly(original);
    }
}
