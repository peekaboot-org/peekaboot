package org.peekaboot.backend.insights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AggregateStatsTest {

    @Test
    void computesStatsOverPlainValues() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        AggregateStats stats = AggregateStats.of(values);
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(10);
        assertThat(stats.avg()).isEqualTo(5.5);
        assertThat(stats.median()).isEqualTo(5);   // nearest-rank: ceil(0.5*10)=5th
        assertThat(stats.p90()).isEqualTo(9);      // ceil(0.9*10)=9th
        assertThat(stats.p95()).isEqualTo(10);     // ceil(0.95*10)=10th
        assertThat(stats.p99()).isEqualTo(10);
        assertThat(stats.samples()).isEqualTo(10);
    }

    @Test
    void filtersNaNSamples() {
        AggregateStats stats = AggregateStats.of(new double[]{Double.NaN, 4.0, Double.NaN, 2.0});
        assertThat(stats.min()).isEqualTo(2.0);
        assertThat(stats.max()).isEqualTo(4.0);
        assertThat(stats.avg()).isEqualTo(3.0);
        assertThat(stats.samples()).isEqualTo(2);
    }

    @Test
    void allNaNYieldsNaNEntryWithZeroSamples() {
        AggregateStats stats = AggregateStats.of(new double[]{Double.NaN, Double.NaN});
        assertThat(stats.min()).isNaN();
        assertThat(stats.avg()).isNaN();
        assertThat(stats.p99()).isNaN();
        assertThat(stats.samples()).isZero();
    }

    @Test
    void singleValue() {
        AggregateStats stats = AggregateStats.of(new double[]{7.0});
        assertThat(stats.min()).isEqualTo(7.0);
        assertThat(stats.median()).isEqualTo(7.0);
        assertThat(stats.p99()).isEqualTo(7.0);
        assertThat(stats.samples()).isEqualTo(1);
    }

    @Test
    void aggregatesFinerEntries() {
        // two 1m entries: (min 1, max 9, avg 4, 6 samples) and (min 2, max 20, avg 8, 2 samples)
        AggregateStats stats = AggregateStats.ofAggregates(
                new double[]{1, 2}, new double[]{9, 20}, new double[]{4, 8}, new double[]{6, 2});
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(20);
        // weighted avg: (4*6 + 8*2) / 8 = 5.0
        assertThat(stats.avg()).isCloseTo(5.0, within(1e-9));
        // percentiles over the avg values [4, 8]
        assertThat(stats.median()).isEqualTo(4);
        assertThat(stats.p99()).isEqualTo(8);
        assertThat(stats.samples()).isEqualTo(8);
    }

    @Test
    void aggregatesSkipNaNEntries() {
        AggregateStats stats = AggregateStats.ofAggregates(
                new double[]{Double.NaN, 2}, new double[]{Double.NaN, 4},
                new double[]{Double.NaN, 3}, new double[]{0, 5});
        assertThat(stats.min()).isEqualTo(2);
        assertThat(stats.avg()).isEqualTo(3);
        assertThat(stats.samples()).isEqualTo(5);
    }
}
