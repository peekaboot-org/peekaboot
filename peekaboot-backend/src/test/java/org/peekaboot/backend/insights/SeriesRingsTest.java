package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
