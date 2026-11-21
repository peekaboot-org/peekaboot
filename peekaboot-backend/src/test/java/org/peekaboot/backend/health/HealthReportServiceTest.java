package org.peekaboot.backend.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.insights.InsightsDataReader;
import org.peekaboot.backend.insights.ProblemRule;
import org.peekaboot.backend.insights.TickedInsights;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.FailedTraces;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInput;

/** The report's status and the code it answers with, over real parts and Boot's code mapping as data. */
class HealthReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** Boot's {@code SimpleHttpCodeStatusMapper} defaults, plus one application-defined status. */
    private static final HealthStatusCodes BOOT_MAPPING = status -> switch (status) {
        case "DOWN", "OUT_OF_SERVICE" -> 503;
        case "DEGRADED" -> 500;
        default -> 200;
    };

    private final InMemoryTraceStore store = TraceStores.withDefaults();
    private final ErrorDigest digest = new ErrorDigest();

    private static Supplier<@Nullable Object> reading(String status) {
        return () -> Map.of("status", status, "components", Map.of("db", Map.of("status", status)));
    }

    private HealthReportService service(
            @Nullable Supplier<@Nullable Object> health,
            @Nullable InsightsDataReader insights,
            boolean failOnProblems) {
        return serviceOver(health, findings(insights), failOnProblems);
    }

    private RecentFindings findings(@Nullable InsightsDataReader insights) {
        return new RecentFindings(
                insights, store, digest, new MaskingEngine(), WINDOW, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HealthReportService serviceOver(
            @Nullable Supplier<@Nullable Object> health, RecentFindings findings, boolean failOnProblems) {
        return new HealthReportService(
                health,
                new ActuatorResponseParser(),
                new HealthComponents(new MaskingEngine(), List.of()),
                findings,
                BOOT_MAPPING,
                failOnProblems);
    }

    @Test
    void aDownHealthAnswers503WithTheActuatorsStatus() {
        HealthReportService.HealthAnswer answer =
                service(reading("DOWN"), null, false).report();

        assertThat(answer.httpStatus()).isEqualTo(503);
        assertThat(answer.report().status()).isEqualTo("DOWN");
        assertThat(answer.report().components()).containsOnlyKeys("db");
    }

    @Test
    void anUpHealthAnswers200() {
        HealthReportService.HealthAnswer answer =
                service(reading("UP"), null, false).report();

        assertThat(answer.httpStatus()).isEqualTo(200);
        assertThat(answer.report().status()).isEqualTo("UP");
    }

    @Test
    void anErrorProblemLeavesTheCodeToHealthUnlessOptedIn() {
        HealthReportService.HealthAnswer answer = service(reading("UP"), TickedInsights.withConnectionsWaiting(), false)
                .report();

        assertThat(answer.httpStatus()).isEqualTo(200);
        assertThat(answer.report().problems()).extracting("rule").containsExactly(ProblemRule.POOL_WAITING);
    }

    @Test
    void optedInAnErrorProblemAnswers503AndKeepsTheStatusUp() {
        HealthReportService.HealthAnswer answer = service(reading("UP"), TickedInsights.withConnectionsWaiting(), true)
                .report();

        assertThat(answer.httpStatus()).isEqualTo(503);
        assertThat(answer.report().status()).isEqualTo("UP");
    }

    @Test
    void optedInAWarningProblemLeavesTheCodeAlone() {
        HealthReportService.HealthAnswer answer =
                service(reading("UP"), TickedInsights.withHeapNearMax(), true).report();

        assertThat(answer.report().problems()).extracting("rule").containsExactly(ProblemRule.HEAP_NEAR_MAX);
        assertThat(answer.httpStatus()).isEqualTo(200);
    }

    @Test
    void aNon2xxCodeFromTheMappingStaysWhenProblemsAlsoFail() {
        HealthReportService.HealthAnswer answer = service(
                        reading("DEGRADED"), TickedInsights.withConnectionsWaiting(), true)
                .report();

        assertThat(answer.httpStatus()).isEqualTo(500);
    }

    @Test
    void anApplicationDefinedStatusIsServedVerbatim() {
        HealthReportService.HealthAnswer answer =
                service(reading("DEGRADED"), null, false).report();

        assertThat(answer.report().status()).isEqualTo("DEGRADED");
        assertThat(answer.httpStatus()).isEqualTo(500);
    }

    @Test
    void withoutAHealthReadingTheStatusIsUnknownAndComponentsEmpty() {
        for (Supplier<@Nullable Object> absent : new Supplier[] {null, (Supplier<@Nullable Object>) () -> null}) {
            HealthReportService.HealthAnswer answer =
                    service(absent, null, false).report();

            assertThat(answer.report().status()).isEqualTo("UNKNOWN");
            assertThat(answer.report().components()).isEmpty();
            assertThat(answer.httpStatus()).isEqualTo(200);
        }
    }

    @Test
    void aReadingWithoutAStatusIsUnknown() {
        HealthReportService.HealthAnswer answer =
                service(() -> Map.of("status", " "), null, false).report();

        assertThat(answer.report().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void carriesTheFindingsBesideTheComponents() {
        FailedTraces.recorder(store, digest)
                .liveInputs()
                .accept(new TraceInput.SpanArrived(FailedTraces.root("e1", NOW.minusSeconds(60), 500)));
        RecentFindings findings = findings(TickedInsights.withConnectionsWaiting());

        HealthReport report =
                serviceOver(reading("UP"), findings, false).report().report();

        RecentFindings.Findings expected = findings.read();
        assertThat(report.windowMs()).isEqualTo(expected.windowMs());
        assertThat(report.traces()).isEqualTo(expected.traces()).isEqualTo(new HealthReport.Traces(1, 0));
        assertThat(report.failures()).isEqualTo(expected.failures()).hasSize(1);
        assertThat(report.problems()).isEqualTo(expected.problems()).hasSize(1);
    }
}
