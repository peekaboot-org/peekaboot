package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;

class SeriesRingsTest {

    @Test
    void memoryEstimateMatchesFormula() {
        // 2 series x (90 + 1440*8 + 720*8) x 8 bytes
        List<InsightsProperties.Level> spec = List.of(
                InsightsProperties.Level.of(Duration.ofSeconds(10), 90),
                InsightsProperties.Level.of(Duration.ofMinutes(1), 1440),
                InsightsProperties.Level.of(Duration.ofHours(1), 720));
        assertThat(SeriesRings.estimateMemoryBytes(2, spec)).isEqualTo(2L * (90 + 1440 * 8 + 720 * 8) * 8);
    }
}
