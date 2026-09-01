package org.peekaboot.backend.insights.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        properties.setLevels(List.of(level(Duration.ofSeconds(10), 90), level(Duration.ofSeconds(25), 100)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple");
    }

    /** A roll-up reads the previous ring; a window wider than that ring would aggregate a partial window silently. */
    @Test
    void rejectsARollUpWindowWiderThanThePreviousRing() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(level(Duration.ofSeconds(10), 5), level(Duration.ofMinutes(1), 10)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ring");
    }

    @Test
    void rejectsEmptyLevelsAndBadSizes() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of());
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties.setLevels(List.of(level(Duration.ofSeconds(10), 0)));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPositivePersistenceInterval() {
        InsightsProperties properties = new InsightsProperties();
        properties.getPersistence().setInterval(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interval");

        properties.getPersistence().setInterval(Duration.ofSeconds(-1));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void rejectsNonPositivePersistenceMaxAge() {
        InsightsProperties properties = new InsightsProperties();
        properties.getPersistence().setMaxAge(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-age");

        properties.getPersistence().setMaxAge(Duration.ofSeconds(-1));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-age");
    }

    /** A YAML null binds through the setter, so both blocks have to say so rather than fail on a dereference. */
    @Test
    void rejectsBlocksThatWereBoundToNothing() {
        InsightsProperties properties = new InsightsProperties();
        properties.setPersistence(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistence");

        InsightsProperties withoutLevels = new InsightsProperties();
        withoutLevels.setLevels(null);
        assertThatThrownBy(withoutLevels::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("levels");
    }

    @Test
    void persistenceDefaultsToOneWritePerCoarsestWindowAndItsWholeSpan() {
        InsightsProperties properties = new InsightsProperties();

        assertThat(properties.resolvePersistenceInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.resolvePersistenceMaxAge()).isEqualTo(Duration.ofHours(720));
    }
}
