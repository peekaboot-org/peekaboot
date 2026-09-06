package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import ch.qos.logback.classic.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.testsupport.LogCapture;

class PeekabootActuatorServiceTest {

    @Test
    void getInsightsData_keysEachSourceByItsId() {
        PeekabootActuatorService service = new PeekabootActuatorService(List.of(
                new InsightsSource("spring", () -> Map.of("bootVersion", "4.1.1")),
                new InsightsSource("info", () -> Map.of("app", "demo"))));

        assertThat(service.getInsightsData())
                .containsExactly(entry("spring", Map.of("bootVersion", "4.1.1")), entry("info", Map.of("app", "demo")));
    }

    /** A source whose backing bean is absent says so with null rather than failing. */
    @Test
    void getInsightsData_leavesOutASourceThatReadsNullWithoutLogging() {
        try (LogCapture capture = LogCapture.attach(PeekabootActuatorService.class, Level.DEBUG)) {
            PeekabootActuatorService service = new PeekabootActuatorService(
                    List.of(new InsightsSource("health", () -> null), new InsightsSource("info", () -> Map.of())));

            assertThat(service.getInsightsData()).doesNotContainKey("health").containsKey("info");
            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void getInsightsData_leavesOutASourceThatFailsAndWarnsWithTheCauseOnceOnly() {
        try (LogCapture capture = LogCapture.attach(PeekabootActuatorService.class, Level.DEBUG)) {
            PeekabootActuatorService service = new PeekabootActuatorService(List.of(
                    new InsightsSource("loggers", () -> {
                        throw new IllegalStateException("boom");
                    }),
                    new InsightsSource("info", () -> Map.of("app", "demo"))));

            Map<String, Object> data = service.getInsightsData();
            service.getInsightsData();

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
