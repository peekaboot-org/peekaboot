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
        StatsRing ring = new StatsRing(3);
        ring.add(entry(1));
        ring.add(entry(2));
        double[][] columns = ring.toColumns();
        assertThat(columns.length).isEqualTo(8);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("avg")]).containsExactly(1.0, 2.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("min")]).containsExactly(1.0, 2.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("samples")]).containsExactly(1.0, 1.0);
    }

    @Test
    void restoreRefillsAnEmptyRingFromToColumnsOutput() {
        StatsRing source = new StatsRing(3);
        source.add(entry(1));
        source.add(entry(2));

        StatsRing restored = new StatsRing(3);
        restored.restore(source.toColumns());

        assertThat(restored.size()).isEqualTo(2);
        assertThat(restored.toArrays().get("avg")).containsExactly(1.0, 2.0);
        assertThat(restored.last(2)).extracting(AggregateStats::samples).containsExactly(1, 1);
    }
}
