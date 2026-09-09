package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;

class SeriesRingsTest {

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
