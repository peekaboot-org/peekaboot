package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.health.HealthComponents;
import org.peekaboot.backend.health.HealthReportService;
import org.peekaboot.backend.health.RecentFindings;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.http.ResponseEntity;

/** The controller serves the service's answer as it stands: the status code decided there, the report as body. */
class HealthControllerTest {

    private static HealthReportService serviceReading(String status) {
        MaskingEngine masking = new MaskingEngine();
        return new HealthReportService(
                () -> Map.of("status", status, "components", Map.of("db", Map.of("status", status))),
                new ActuatorResponseParser(),
                new HealthComponents(masking, List.of()),
                new RecentFindings(null, null, null, masking, Duration.ofMinutes(15), Clock.systemUTC()),
                code -> "DOWN".equals(code) ? 503 : 200,
                false);
    }

    private static void assertAnswersAsTheServiceDecided(HealthReportService service) {
        ResponseEntity<HealthReport> response = new HealthController(service).health();
        HealthReportService.HealthAnswer answer = service.report();

        assertThat(response.getStatusCode().value()).isEqualTo(answer.httpStatus());
        assertThat(response.getBody()).isEqualTo(answer.report());
    }

    @Test
    void answersTheReportWithTheCodeTheServiceDecided() {
        HealthReportService service = serviceReading("DOWN");

        assertAnswersAsTheServiceDecided(service);
        assertThat(new HealthController(service).health().getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void answersAnUpReportWith200() {
        HealthReportService service = serviceReading("UP");

        assertAnswersAsTheServiceDecided(service);
        assertThat(new HealthController(service).health().getStatusCode().value())
                .isEqualTo(200);
    }
}
