package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;

class SeriesRingsTest {

    private final List<InsightsProperties.Level> levels = List.of(
            InsightsProperties.Level.of(Duration.ofSeconds(10), 6),
            InsightsProperties.Level.of(Duration.ofMinutes(1), 10));

    @Test
    void appendTickGapsASeriesTheTickLacksAndIgnoresUnknownIds() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));

        rings.appendTick(10_000, Map.of("a", 1.0, "unknown", 9.0));

        LevelSnapshot snapshot = rings.snapshot(0);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.tickValues().get("a")).containsExactly(1.0);
        assertThat(snapshot.tickValues().get("b")).containsExactly(Double.NaN);
    }

    @Test
    void appendStatsGapsASeriesTheRollUpLacksAndIgnoresUnknownIds() {
        SeriesRings rings = new SeriesRings(levels, List.of("a", "b"));
        AggregateStats stats = AggregateStats.of(new double[] {1.0, 3.0});

        rings.appendStats(1, 60_000, Map.of("a", stats, "unknown", stats));

        LevelSnapshot snapshot = rings.snapshot(1);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.statValues().get("a").get("avg")).containsExactly(2.0);
        assertThat(snapshot.statValues().get("b").get("avg")).containsExactly(Double.NaN);
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
