package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;

class SeriesRingsTest {

    private final List<InsightsProperties.Level> levels = List.of(
            InsightsProperties.Level.of(Duration.ofSeconds(10), 6),
            InsightsProperties.Level.of(Duration.ofMinutes(1), 10));

    /** Two one-minute windows of series {@code g}: six samples averaging 5, then two averaging 8. */
    private SeriesRings twoWindows() {
        SeriesRings rings = new SeriesRings(levels, List.of("g"));
        rings.appendStats(1, 60_000, Map.of("g", new AggregateStats(1, 9, 5, 5, 9, 9, 9, 6)));
        rings.appendStats(1, 120_000, Map.of("g", new AggregateStats(2, 20, 8, 8, 20, 20, 20, 2)));
        return rings;
    }

    @Test
    void appendTickGapsASeriesTheTickLacksAndIgnoresUnknownIds() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));

        rings.appendTick(10_000, Map.of("a", 1.0, "unknown", 9.0));

        TickLevelData data = (TickLevelData) rings.levelData(0, null);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.values().get("a")).containsExactly(1.0);
        assertThat(data.values().get("b")).containsExactly(Double.NaN);
    }

    @Test
    void appendStatsGapsASeriesTheRollUpLacksAndIgnoresUnknownIds() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));
        AggregateStats stats = AggregateStats.of(new double[] {1.0, 3.0});

        rings.appendStats(1, 60_000, Map.of("a", stats, "unknown", stats));

        WindowLevelData data = (WindowLevelData) rings.levelData(1, null);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.statistics().get("a").get(WindowStatistic.AVG)).containsExactly(2.0);
        assertThat(data.statistics().get("b").get(WindowStatistic.AVG)).containsExactly(Double.NaN);
        assertThat(data.samples().get("a"))
                .as("the samples behind each window, which the summary's average weighs by")
                .containsExactly(2.0);
        assertThat(data.samples().get("b")).containsExactly(0.0);
    }

    @Test
    void sinceKeepsTheWindowsDatedAtOrAfterIt() {
        LevelData newest = twoWindows().levelData(1, Instant.ofEpochMilli(120_000));

        assertThat(newest.count()).isEqualTo(1);
        assertThat(newest.timestamps()).containsExactly(120_000L);
        assertThat(newest.summaryOf("g")).isEqualTo(new SeriesSummary(2.0, 8.0, 20.0, 8.0, 20.0, 8.0, 2));
    }

    /** An ISO instant can lie past the epoch-millisecond range; it still keeps no sample, or every one. */
    @Test
    void aSincePastTheMillisecondRangeKeepsNoneOrEvery() {
        assertThat(twoWindows().levelData(1, Instant.MAX).count()).isZero();
        assertThat(twoWindows().levelData(1, Instant.MIN).count()).isEqualTo(2);
    }

    @Test
    void aReadSinceAnInstantCopiesOnlyTheEntriesItKeeps() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));
        for (int tick = 1; tick <= 5; tick++) {
            rings.appendTick(tick * 10_000L, Map.of("a", (double) tick, "b", tick * 10.0));
        }

        TickLevelData data = (TickLevelData) rings.levelData(0, Instant.ofEpochMilli(40_000));

        assertThat(data.count()).isEqualTo(2);
        assertThat(data.values().get("a")).containsExactly(4.0, 5.0);
        assertThat(data.values().get("b")).containsExactly(40.0, 50.0);
    }

    @Test
    void captureAfterHoldsOnlyTheEntriesDatedAfterThePersistedEnd() {
        SeriesRings rings = new SeriesRings(levels, List.of("a"));
        for (int tick = 1; tick <= 5; tick++) {
            rings.appendTick(tick * 10_000L, Map.of("a", (double) tick));
        }

        InsightsSnapshot.Level level =
                rings.captureAfter(1L, new long[] {30_000, 0}).levels().get(0);

        assertThat(level.count()).isEqualTo(2);
        assertThat(level.endEpochMs()).isEqualTo(50_000);
        assertThat(rings.captureAfter(1L, new long[] {30_000, 0})
                        .series()
                        .get("a")
                        .get(0)[0])
                .containsExactly(4.0, 5.0);
    }

    @Test
    void captureAfterNothingPersistedHoldsEverything() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));
        for (int tick = 0; tick < 4; tick++) {
            rings.appendTick(5_000 + tick * 10_000L, Map.of("a", (double) tick, "b", tick * 10.0));
        }
        rings.appendStats(1, 60_000, Map.of("a", AggregateStats.of(new double[] {1.0, 3.0})));
        InsightsSnapshot everything = rings.capture(1L);

        InsightsSnapshot after = rings.captureAfter(1L, new long[levels.size()]);

        assertThat(after.levels()).isEqualTo(everything.levels());
        assertThat(after.levels().get(0).count())
                .as("the ticks start off the interval grid, so the end alone would count fewer")
                .isEqualTo(4);
        assertThat(after.levels().get(1).count()).isEqualTo(1);
        everything.series().forEach((id, byLevel) -> {
            for (int level = 0; level < byLevel.size(); level++) {
                double[][] expected = byLevel.get(level);
                double[][] actual = after.series().get(id).get(level);
                assertThat(actual).hasNumberOfRows(expected.length);
                for (int column = 0; column < expected.length; column++) {
                    assertThat(actual[column]).containsExactly(expected[column]);
                }
            }
        });
    }

    @Test
    void captureAfterCountsTheGapsABurstFilled() {
        SeriesRings rings = new SeriesRings(levels, List.of("a"));
        rings.appendTick(10_000, Map.of("a", 1.0));
        rings.appendTick(50_000, Map.of("a", 5.0));

        InsightsSnapshot after = rings.captureAfter(1L, new long[] {10_000, 0});

        assertThat(after.levels().get(0).count()).isEqualTo(4);
        assertThat(after.series().get("a").get(0)[0]).containsExactly(Double.NaN, Double.NaN, Double.NaN, 5.0);
    }

    @Test
    void captureAfterAFullRingHoldsTheWholeRing() {
        SeriesRings rings = new SeriesRings(levels, List.of("a"));
        rings.appendTick(10_000, Map.of("a", 1.0));
        rings.appendTick(210_000, Map.of("a", 2.0));

        InsightsSnapshot.Level level =
                rings.captureAfter(1L, new long[] {10_000, 0}).levels().get(0);

        assertThat(level.count()).isEqualTo(6);
        assertThat(level.endEpochMs()).isEqualTo(210_000);
    }

    @Test
    void captureAfterALevelThatHasNotMovedHoldsNothingForIt() {
        SeriesRings rings = new SeriesRings(levels, List.of("a"));
        rings.appendStats(1, 60_000, Map.of("a", AggregateStats.of(new double[] {1.0})));

        InsightsSnapshot after = rings.captureAfter(1L, new long[] {0, 60_000});

        assertThat(after.levels().get(1).count()).isZero();
        assertThat(after.levels().get(1).endEpochMs()).isEqualTo(60_000);
        assertThat(after.levels().get(0).count()).isZero();
        assertThat(after.levels().get(0).endEpochMs()).isZero();
        assertThat(after.series().get("a").get(1)[0]).isEmpty();
    }

    @Test
    void captureAfterCopiesEveryStatColumnOfTheNewWindows() {
        InsightsSnapshot after = twoWindows().captureAfter(1L, new long[] {0, 60_000});

        double[][] columns = after.series().get("g").get(1);
        assertThat(columns).hasNumberOfRows(InsightsSnapshot.STAT_COLUMNS.size());
        assertThat(Arrays.stream(columns).mapToInt(column -> column.length)).containsOnly(1);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("samples")]).containsExactly(2.0);
        assertThat(columns[InsightsSnapshot.STAT_COLUMNS.indexOf("avg")]).containsExactly(8.0);
    }

    /**
     * Three series over a 10-entry tick ring, a 5-entry and a 2-entry aggregated ring. Each
     * tick entry is one double, each aggregated entry eight (seven stats plus the sample
     * count): 3 x (10 + 5 x 8 + 2 x 8) doubles at eight bytes each.
     */
    @Test
    void memoryEstimateCountsOneDoublePerTickAndEightPerAggregatedEntry() {
        List<InsightsProperties.Level> levels = List.of(
                InsightsProperties.Level.of(Duration.ofSeconds(1), 10),
                InsightsProperties.Level.of(Duration.ofMinutes(1), 5),
                InsightsProperties.Level.of(Duration.ofHours(1), 2));

        assertThat(SeriesRings.estimateMemoryBytes(3, levels)).isEqualTo(1_584);
    }
}
