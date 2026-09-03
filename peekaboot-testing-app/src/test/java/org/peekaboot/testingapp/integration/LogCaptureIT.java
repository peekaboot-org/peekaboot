package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Covers the log-capture path end to end without a browser.
 *
 * <p>{@code PeekabootLogbackAppender} keeps a log only when the logging event carries a
 * {@code traceId} in its MDC, and peekaboot never populates that MDC itself - it relies
 * entirely on Micrometer's OpenTelemetry-to-SLF4J bridge. The toolbar's own trace id comes
 * from a different source ({@code tracer.currentSpan()} in {@code DevToolbarFilter}), so a
 * broken MDC bridge still yields a perfectly populated toolbar whose log counts are all
 * zero. Only an assertion on a captured log distinguishes the two.
 *
 * <p>The browser-driven tests that depend on captured logs
 * ({@code ToolbarIT.toolbarShowsErrorLogCountWhenRequestLogsAnError} and
 * {@code TraceOverlayIT.logsFilterChipUsesTheContrastTunedForeground}) fail with an
 * opaque Playwright timeout when this breaks. This test fails with the actual count
 * instead, and pins the boundary: green here plus red there means the regression is in the
 * frontend render, not in capture.
 *
 * <p>Uses the real auto-configured tracer deliberately - {@code SharedToolbarTestConfig}'s
 * stand-in {@code Tracer} is NOOP-backed and populates no MDC, which is exactly the
 * condition under test.
 *
 * <p>Waiting for the spans is enough to know the logs are in: spans arrive via the OTel
 * BatchSpanProcessor whereas logs are published synchronously during the request, so a
 * trace that has spans has necessarily already received any log it will ever get.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogCaptureIT {

    @LocalServerPort
    private int port;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void errorLoggedInsideRequestIsCapturedAgainstThatRequestsTrace() {
        String traceId = traces.triggerAndCaptureTraceId("/?error=true");

        JsonNode summary = traces.awaitTrace(traceId).path("summary");
        JsonNode logs = summary.path("logs");

        assertThat(logs.isMissingNode() || logs.isNull())
                .as(
                        "trace %s carried spans but no logs section at all - nothing reached "
                                + "PeekabootLogbackAppender, so MDC correlation is not populating traceId",
                        traceId)
                .isFalse();

        assertThat(logs.path("errorCount").asInt())
                .as(
                        "the ERROR PersonController logs for /?error=true must be captured against "
                                + "trace %s; a count of 0 means the log event carried no MDC traceId",
                        traceId)
                .isEqualTo(1);
    }

    /**
     * A request that logs nothing at ERROR must not report an error count - guards the
     * assertion above against passing on any trace that merely happens to hold an error.
     */
    @Test
    void requestWithoutAnErrorLogReportsNoErrorCount() {
        String traceId = traces.triggerAndCaptureTraceId("/persons");

        JsonNode logs = traces.awaitTrace(traceId).path("summary").path("logs");

        assertThat(logs.path("errorCount").asInt())
                .as("/persons logs no ERROR, so trace %s must report none", traceId)
                .isZero();
    }
}
