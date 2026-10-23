package org.peekaboot.backend.insights.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InsightsPropertiesTest {

    /**
     * The level geometry is the persisted snapshot's compatibility key: a file written for
     * other levels is discarded on load, so a change to these defaults silently invalidates
     * every user's history and has to be deliberate.
     */
    @Test
    void theDefaultLevelsAreThePersistedHistorysCompatibilityKey() {
        InsightsProperties properties = new InsightsProperties();
        assertThat(properties.getLevels())
                .extracting(InsightsProperties.Level::getInterval, InsightsProperties.Level::getSize)
                .containsExactly(
                        tuple(Duration.ofSeconds(10), 90),
                        tuple(Duration.ofMinutes(1), 1440),
                        tuple(Duration.ofHours(1), 720));
        properties.validate(); // must not throw
    }

    @Test
    void rejectsNonMultipleIntervals() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(
                InsightsProperties.Level.of(Duration.ofSeconds(10), 90),
                InsightsProperties.Level.of(Duration.ofSeconds(25), 100)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple");
    }

    /** A roll-up reads the previous ring; a window wider than that ring would aggregate a partial window silently. */
    @Test
    void rejectsARollUpWindowWiderThanThePreviousRing() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(
                InsightsProperties.Level.of(Duration.ofSeconds(10), 5),
                InsightsProperties.Level.of(Duration.ofMinutes(1), 10)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ring");
    }

    @Test
    void rejectsEmptyLevels() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one level");
    }

    @Test
    void rejectsANonPositiveSize() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(InsightsProperties.Level.of(Duration.ofSeconds(10), 0)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size must be > 0");
    }

    /**
     * The snapshot codec refuses a file whose header claims more levels or a wider ring
     * than it holds plausible; a configuration past those bounds would start, persist
     * at shutdown, and lose its history on the next start. So it is refused up front.
     */
    @Test
    void rejectsMoreLevelsThanTheSnapshotCanHold() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(IntStream.range(0, 17)
                .mapToObj(
                        i -> InsightsProperties.Level.of(Duration.ofSeconds(10).multipliedBy(1L << i), 2))
                .toList());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16")
                .hasMessageContaining("snapshot");
    }

    @Test
    void rejectsARingWiderThanTheSnapshotCanHold() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(InsightsProperties.Level.of(Duration.ofSeconds(10), 1_000_001)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1000000")
                .hasMessageContaining("snapshot");
    }

    /**
     * The rings, the roll-up and the boundary schedule all count in whole milliseconds; a
     * sub-millisecond level would divide by zero on its own thread, which then never samples.
     */
    @Test
    void rejectsALevelIntervalBelowOneMillisecond() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(InsightsProperties.Level.of(Duration.ofNanos(500_000), 90)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "peekaboot.insights.levels: interval must be a positive whole number of milliseconds");
    }

    /** As a finer level, the same interval used to fail the roll-up check with a bare ArithmeticException. */
    @Test
    void rejectsASubMillisecondIntervalBeneathACoarserLevel() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(
                InsightsProperties.Level.of(Duration.ofNanos(500_000), 90),
                InsightsProperties.Level.of(Duration.ofSeconds(10), 90)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "peekaboot.insights.levels: interval must be a positive whole number of milliseconds");
    }

    /** {@code 1500us} would be truncated to 1ms everywhere it is used, so it is refused rather than misread. */
    @Test
    void rejectsALevelIntervalThatIsNoWholeNumberOfMilliseconds() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(List.of(InsightsProperties.Level.of(Duration.ofNanos(1_500_000), 90)));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole number of milliseconds");
    }

    /** The snapshot writer schedules in whole milliseconds; below one it would die on its first boundary. */
    @Test
    void rejectsAPersistenceIntervalBelowOneMillisecond() {
        InsightsProperties properties = new InsightsProperties();
        properties.getPersistence().setInterval(Duration.ofNanos(500_000));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "peekaboot.insights.persistence.interval must be a positive whole number of milliseconds");
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

    /** A YAML null binds through the setter, so the block has to say so rather than fail on a dereference. */
    @Test
    void rejectsAPersistenceBlockBoundToNothing() {
        InsightsProperties properties = new InsightsProperties();
        properties.setPersistence(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peekaboot.insights.persistence must not be null");
    }

    /** A YAML null binds through the setter, so the list has to say so rather than fail on a dereference. */
    @Test
    void rejectsALevelsListBoundToNothing() {
        InsightsProperties properties = new InsightsProperties();
        properties.setLevels(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peekaboot.insights.levels must contain at least one level");
    }

    @Test
    void persistenceDefaultsToOneWritePerCoarsestWindowAndItsWholeSpan() {
        InsightsProperties properties = new InsightsProperties();

        assertThat(properties.resolvePersistenceInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.resolvePersistenceMaxAge()).isEqualTo(Duration.ofDays(30)); // 720 one-hour slots
    }
}
