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
}
