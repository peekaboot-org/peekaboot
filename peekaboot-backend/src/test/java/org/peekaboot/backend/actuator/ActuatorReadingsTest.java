package org.peekaboot.backend.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import ch.qos.logback.classic.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;

class ActuatorReadingsTest {

    @Test
    void readAll_keysEachSourceByItsId() {
        ActuatorReadings readings = new ActuatorReadings(List.of(
                new InsightsSource("spring", () -> Map.of("bootVersion", "4.1.1")),
                new InsightsSource("info", () -> Map.of("app", "demo"))));

        assertThat(readings.readAll())
                .containsExactly(entry("spring", Map.of("bootVersion", "4.1.1")), entry("info", Map.of("app", "demo")));
    }

    /** A source whose backing bean is absent says so with null rather than failing. */
    @Test
    void readAll_leavesOutASourceThatReadsNullWithoutLogging() {
        try (LogCapture capture = LogCapture.attach(ActuatorReadings.class, Level.DEBUG)) {
            ActuatorReadings readings = new ActuatorReadings(
                    List.of(new InsightsSource("health", () -> null), new InsightsSource("info", () -> Map.of())));

            assertThat(readings.readAll()).doesNotContainKey("health").containsKey("info");
            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void readAll_leavesOutASourceThatFailsAndWarnsWithTheCauseOnceOnly() {
        try (LogCapture capture = LogCapture.attach(ActuatorReadings.class, Level.DEBUG)) {
            ActuatorReadings readings = new ActuatorReadings(List.of(
                    new InsightsSource("loggers", () -> {
                        throw new IllegalStateException("boom");
                    }),
                    new InsightsSource("info", () -> Map.of("app", "demo"))));

            Map<String, Object> data = readings.readAll();
            readings.readAll();

            assertThat(data).doesNotContainKey("loggers").containsKey("info");
            assertThat(capture.appender().list).hasSize(2);
            assertThat(capture.appender().list.get(0)).satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Actuator endpoint 'loggers' failed");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
            });
            assertThat(capture.appender().list.get(1)).satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Actuator endpoint 'loggers' failed again: java.lang.IllegalStateException: boom");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }
}
