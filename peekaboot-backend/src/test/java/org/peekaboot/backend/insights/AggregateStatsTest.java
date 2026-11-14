package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AggregateStatsTest {

    @Test
    void computesStatsOverPlainValues() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        AggregateStats stats = AggregateStats.of(values);
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(10);
        assertThat(stats.avg()).isEqualTo(5.5);
        assertThat(stats.median()).isEqualTo(5); // nearest-rank: ceil(0.5*10)=5th
        assertThat(stats.p90()).isEqualTo(9); // ceil(0.9*10)=9th
        assertThat(stats.p95()).isEqualTo(10); // ceil(0.95*10)=10th
        assertThat(stats.p99()).isEqualTo(10);
        assertThat(stats.samples()).isEqualTo(10);
    }

    @Test
    void byNameListsTheSevenStatsInWireOrder() {
        AggregateStats stats = AggregateStats.of(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertThat(stats.byName())
                .containsExactly(
                        entry("min", 1.0),
                        entry("max", 10.0),
                        entry("avg", 5.5),
                        entry("median", 5.0),
                        entry("p90", 9.0),
                        entry("p95", 10.0),
                        entry("p99", 10.0));
        assertThat(AggregateStats.STAT_NAMES)
                .containsExactlyElementsOf(stats.byName().keySet());
    }

    @Test
    void filtersNaNSamples() {
        AggregateStats stats = AggregateStats.of(new double[] {Double.NaN, 4.0, Double.NaN, 2.0});
        assertThat(stats.min()).isEqualTo(2.0);
        assertThat(stats.max()).isEqualTo(4.0);
        assertThat(stats.avg()).isEqualTo(3.0);
        assertThat(stats.samples()).isEqualTo(2);
    }

    @Test
    void allNaNYieldsNaNEntryWithZeroSamples() {
        AggregateStats stats = AggregateStats.of(new double[] {Double.NaN, Double.NaN});
        assertThat(stats.min()).isNaN();
        assertThat(stats.avg()).isNaN();
        assertThat(stats.p99()).isNaN();
        assertThat(stats.samples()).isZero();
    }

    @Test
    void singleValue() {
        AggregateStats stats = AggregateStats.of(new double[] {7.0});
        assertThat(stats.min()).isEqualTo(7.0);
        assertThat(stats.median()).isEqualTo(7.0);
        assertThat(stats.p99()).isEqualTo(7.0);
        assertThat(stats.samples()).isEqualTo(1);
    }

    @Test
    void aggregatesFinerEntries() {
        // two 1m entries: (min 1, max 9, avg 4, median 3, 6 samples) and (min 2, max 20, avg 8, median 7, 2 samples)
        AggregateStats stats =
                AggregateStats.ofAggregates(List.of(finer(1, 9, 4, 3, 9, 9, 9, 6), finer(2, 20, 8, 7, 20, 20, 20, 2)));
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(20);
        // weighted avg: (4*6 + 8*2) / 8 = 5.0
        assertThat(stats.avg()).isCloseTo(5.0, within(1e-9));
        // the 4th of 8 samples lies in the first entry, the 8th in the second
        assertThat(stats.median()).isEqualTo(3);
        assertThat(stats.p99()).isEqualTo(20);
        assertThat(stats.samples()).isEqualTo(8);
    }

    @Test
    void aggregatesSkipNaNEntries() {
        AggregateStats stats =
                AggregateStats.ofAggregates(List.of(AggregateStats.EMPTY, finer(2, 4, 3, 3, 4, 4, 4, 5)));
        assertThat(stats.min()).isEqualTo(2);
        assertThat(stats.avg()).isEqualTo(3);
        assertThat(stats.samples()).isEqualTo(5);
    }

    @Test
    void aCoarserWindowTakesEachPercentileFromTheFinerWindowsSamePercentile() {
        AggregateStats stats = AggregateStats.ofAggregates(List.of(
                finer(1, 1, 1, 1, 10, 11, 12, 6),
                finer(1, 1, 1, 2, 20, 21, 22, 6),
                finer(1, 1, 1, 3, 30, 31, 32, 6),
                finer(1, 1, 1, 4, 40, 41, 42, 6)));

        // of 24 samples the 12th lies in the second entry, the 22nd, 23rd and 24th in the fourth
        assertThat(stats.median()).isEqualTo(2.0);
        assertThat(stats.p90()).isEqualTo(40.0);
        assertThat(stats.p95()).isEqualTo(41.0);
        assertThat(stats.p99()).isEqualTo(42.0);
    }

    @Test
    void aCoarserWindowRanksEachPercentileAtItsOwnQuantile() {
        List<AggregateStats> oneSampleEach = IntStream.rangeClosed(1, 20)
                .mapToObj(value -> AggregateStats.of(new double[] {value}))
                .toList();

        AggregateStats stats = AggregateStats.ofAggregates(oneSampleEach);

        // of 20 samples: ceil(0.5*20)=10th, ceil(0.9*20)=18th, ceil(0.95*20)=19th, ceil(0.99*20)=20th
        assertThat(stats.median()).isEqualTo(10.0);
        assertThat(stats.p90()).isEqualTo(18.0);
        assertThat(stats.p95()).isEqualTo(19.0);
        assertThat(stats.p99()).isEqualTo(20.0);
    }

    @Test
    void aCoarserPercentileWeighsEachFinerWindowByItsSamples() {
        AggregateStats stats = AggregateStats.ofAggregates(
                List.of(finer(1, 1, 1, 1, 1, 1, 1, 1), finer(1, 100, 1, 100, 100, 100, 100, 9)));

        assertThat(stats.median()).isEqualTo(100.0);
    }

    @Test
    void aFinerWindowWithoutAPercentileIsLeftOutOfIt() {
        AggregateStats without = finer(1, 9, 4, 3, 9, Double.NaN, 9, 6);
        AggregateStats with = finer(2, 20, 8, 7, 20, 15, 20, 2);

        assertThat(AggregateStats.ofAggregates(List.of(without, with)).p95()).isEqualTo(15.0);
        assertThat(AggregateStats.ofAggregates(List.of(without, without)).p95()).isNaN();
    }

    /** A window that counts no sample has no rank to give, so its percentile stands for nothing. */
    @Test
    void finerWindowsWithoutSamplesGiveNoPercentile() {
        AggregateStats stats =
                AggregateStats.ofAggregates(List.of(finer(1, 9, 4, 3, 9, 9, 9, 0), finer(2, 20, 8, 7, 20, 20, 20, 0)));

        assertThat(stats.median()).isNaN();
        assertThat(stats.p99()).isNaN();
    }

    /**
     * Six samples a window is the default first roll-up. Its p90, p95 and p99 are its maximum, so a
     * coarser tail ranks maxima and cannot fall below the tail of the samples themselves.
     */
    @Test
    void atTheDefaultLevelsACoarserTailIsNeverBelowTheTrueOne() {
        Random random = new Random(42);
        double[] all = random.doubles(60 * 6).toArray();
        List<AggregateStats> level1 = IntStream.range(0, 60)
                .mapToObj(window -> AggregateStats.of(Arrays.copyOfRange(all, window * 6, window * 6 + 6)))
                .toList();

        AggregateStats coarse = AggregateStats.ofAggregates(level1);

        AggregateStats truth = AggregateStats.of(all);
        assertThat(coarse.p90()).isGreaterThanOrEqualTo(truth.p90());
        assertThat(coarse.p95()).isGreaterThanOrEqualTo(truth.p95());
        assertThat(coarse.p99()).isGreaterThanOrEqualTo(truth.p99()).isLessThanOrEqualTo(coarse.max());
        assertThat(coarse.median()).isBetween(truth.min(), truth.max());
    }

    private static AggregateStats finer(
            double min, double max, double avg, double median, double p90, double p95, double p99, int samples) {
        return new AggregateStats(min, max, avg, median, p90, p95, p99, samples);
    }
}
