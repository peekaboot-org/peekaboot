package org.peekaboot.backend.health;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.api.IssueSeverity;
import org.peekaboot.backend.domain.health.HealthReport;
import org.springframework.http.HttpStatusCode;

/**
 * Builds the health report and decides the code it answers with. The code is the application's own
 * for the health status. With {@code failOnProblems}, a current ERROR problem turns a 2xx code into
 * 503; a code that already says failure stays, and WARNING problems never change it.
 */
public final class HealthReportService {

    private static final String UNKNOWN = "UNKNOWN";
    private static final int SERVICE_UNAVAILABLE = 503;

    private final @Nullable Supplier<@Nullable Object> health;
    private final ActuatorResponseParser parser;
    private final HealthComponents components;
    private final RecentFindings findings;
    private final HealthStatusCodes statusCodes;
    private final boolean failOnProblems;

    /** {@code health} is {@code null} where the application has no health endpoint. */
    public HealthReportService(
            @Nullable Supplier<@Nullable Object> health,
            ActuatorResponseParser parser,
            HealthComponents components,
            RecentFindings findings,
            HealthStatusCodes statusCodes,
            boolean failOnProblems) {
        this.health = health;
        this.parser = parser;
        this.components = components;
        this.findings = findings;
        this.statusCodes = statusCodes;
        this.failOnProblems = failOnProblems;
    }

    public HealthAnswer report() {
        HealthResponse reading = health == null ? null : parser.health(health.get());
        String status =
                reading == null || reading.status() == null || reading.status().isBlank() ? UNKNOWN : reading.status();
        RecentFindings.Findings recent = findings.read();
        HealthReport report = new HealthReport(
                status,
                components.of(reading),
                recent.problems(),
                recent.windowMs(),
                recent.traces(),
                recent.failures());
        int code = statusCodes.of(status);
        boolean errorProblem =
                recent.problems().stream().anyMatch(problem -> problem.severity() == IssueSeverity.ERROR);
        if (failOnProblems && errorProblem && HttpStatusCode.valueOf(code).is2xxSuccessful()) {
            code = SERVICE_UNAVAILABLE;
        }
        return new HealthAnswer(code, report);
    }

    public record HealthAnswer(int httpStatus, HealthReport report) {}
}
