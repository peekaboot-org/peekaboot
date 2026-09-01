package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogCaptureIT {

    /** The toolbar embeds its payload as JSON in a {@code <script id="peekaboot-toolbar-data">} tag. */
    private static final Pattern TOOLBAR_TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static final Duration TRACE_EXPORT_TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_INTERVAL_MS = 50;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void errorLoggedInsideRequestIsCapturedAgainstThatRequestsTrace() {
        String traceId = requestTraceIdFor("/?error=true");

        JsonNode summary = awaitExportedTrace(traceId).path("summary");
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
        String traceId = requestTraceIdFor("/persons");

        JsonNode logs = awaitExportedTrace(traceId).path("summary").path("logs");

        assertThat(logs.path("errorCount").asInt())
                .as("/persons logs no ERROR, so trace %s must report none", traceId)
                .isZero();
    }

    private String requestTraceIdFor(String path) {
        String html = restClient
                .get()
                .uri(path)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        Matcher matcher = TOOLBAR_TRACE_ID.matcher(html == null ? "" : html);
        assertThat(matcher.find())
                .as(
                        "dev toolbar must embed a trace id for %s - without one the request was "
                                + "never traced and the log-capture assertion would be meaningless",
                        path)
                .isTrue();
        return matcher.group(1);
    }

    /**
     * Polls until the trace's spans have been exported, mirroring the toolbar's own
     * completeness rule. Spans arrive via the OTel BatchSpanProcessor (50ms in the test
     * profile) whereas logs are published synchronously during the request, so a trace
     * that has spans has necessarily already received any log it will ever get.
     */
    private JsonNode awaitExportedTrace(String traceId) {
        long deadline = System.nanoTime() + TRACE_EXPORT_TIMEOUT.toNanos();
        JsonNode lastSeen = null;

        while (System.nanoTime() < deadline) {
            JsonNode trace = fetchTraceOrNull(traceId);
            if (trace != null) {
                lastSeen = trace;
                if (trace.path("summary").path("spans").path("count").asInt() > 0) {
                    return trace;
                }
            }
            sleepBriefly();
        }

        throw new AssertionError("trace " + traceId + " never had an exported span within " + TRACE_EXPORT_TIMEOUT
                + "; last response: " + lastSeen);
    }

    private JsonNode fetchTraceOrNull(String traceId) {
        try {
            String body = restClient
                    .get()
                    .uri("/peekaboot/api/traces/{traceId}/insights", traceId)
                    .retrieve()
                    .body(String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (RuntimeException notYetExported) {
            // the endpoint 404s until the first span for the trace lands
            return null;
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for trace export", e);
        }
    }
}
