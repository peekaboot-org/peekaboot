package org.peekaboot.backend.insights.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightsPropertiesTest {

    private static InsightsProperties.Level level(Duration interval, int size) {
        InsightsProperties.Level level = new InsightsProperties.Level();
        level.setInterval(interval);
        level.setSize(size);
        return level;
    }

    @Test
    void defaultsMatchSpec() {
        InsightsProperties properties = new InsightsProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getLevels()).hasSize(3);
        assertThat(properties.getLevels().get(0).getInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getLevels().get(0).getSize()).isEqualTo(90);
        assertThat(properties.getLevels().get(1).getInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getLevels().get(1).getSize()).isEqualTo(1440);
        assertThat(properties.getLevels().get(2).getInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getLevels().get(2).getSize()).isEqualTo(720);
        properties.validate(); // must not throw
    }

    @Test
    void rejectsNonMultipleIntervals() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(
                level(Duration.ofSeconds(10), 90),
                level(Duration.ofSeconds(25), 100)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple");
    }

    @Test
    void rejectsEmptyLevelsAndBadSizes() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of());
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties.setLevels(List.of(level(Duration.ofSeconds(10), 0)));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
