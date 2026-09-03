package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Proves request-parameter and SQL masking end to end through the real HTTP API and a
 * real Spring context - a unit test on the mapper/filter alone does not cover it:
 * RequestCaptureFilter's header masking and QueryExtractor's own unit tests can be green
 * while parameters and the query string leak, because neither exercises those parts.
 * The assertions run against {@code /api/traces/{traceId}/insights}, the only trace
 * endpoint, so it is the only path this masking has to hold on.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RequestAndQueryMaskingIT.MaskingTestEndpoints.class)
class RequestAndQueryMaskingIT {

    /** Creation orders for hand-built spans; only their per-trace ascending order matters. */
    private static final AtomicLong CREATION_ORDER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    /**
     * The fixture endpoints below are registered only for this test (never shipped in the
     * app's own controllers) purely to give a real HTTP request a secret-bearing query
     * parameter and a secret-bearing form field - mirroring
     * {@code PeekabootActuatorServiceIT}'s {@code ThrowingEndpointConfig} pattern for a
     * test-only Spring bean.
     */
    @Test
    void aSecretBearingQueryParameterComesBackMaskedFromTheTraceInsightsApi() {
        traces.restClient()
                .get()
                .uri("/masking-test/search?api_key=AKIAABCDEFGHIJKLMNOP&q=widgets")
                .retrieve()
                .toBodilessEntity();

        JsonNode listed = traces.awaitTraceInBucket("all", "/masking-test/search");
        JsonNode trace = traces.awaitTrace(listed.path("traceId").asString());

        JsonNode queryParams =
                trace.path("httpExchange").path("request").path("params").path("query");
        assertThat(queryParams.path("api_key").get(0).asString()).isEqualTo("******");
        assertThat(queryParams.path("q").get(0).asString()).isEqualTo("widgets");
        assertThat(trace.path("httpExchange").path("request").path("query").asString())
                .isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void aSecretBearingFormFieldComesBackMaskedFromTheTraceInsightsApi() {
        traces.restClient()
                .post()
                .uri("/masking-test/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=alice&password=hunter2")
                .retrieve()
                .toBodilessEntity();

        JsonNode listed = traces.awaitTraceInBucket("all", "/masking-test/login");
        JsonNode trace = traces.awaitTrace(listed.path("traceId").asString());

        JsonNode formParams =
                trace.path("httpExchange").path("request").path("params").path("form");
        assertThat(formParams.path("password").get(0).asString()).isEqualTo("******");
        assertThat(formParams.path("username").get(0).asString()).isEqualTo("alice");
    }

    /**
     * The captured span is written straight to the real TraceStore bean rather than
     * triggered through an actual JDBC call, so this test can isolate what it's actually
     * proving - that the trace insights endpoint's real HTTP-facing response masks the SQL
     * it serves, not just the QueryExtractor unit in isolation - from the separate question
     * of which tag a real driver populates. Using a directly-added SpanData with a
     * "db.statement" tag (the shape QueryExtractorTest already covers in isolation, and the
     * one DashboardTraceViewIT already uses) keeps this test about the HTTP-surface
     * masking behaviour regardless of which of QueryExtractor.findSql's recognised tags
     * ("db.query.text", "db.statement" or "jdbc.query[...]") produced the SQL.
     */
    @Test
    void sqlCarryingACredentialShapedValueComesBackMaskedFromTheTraceInsightsApi() {
        String traceId = "masking-test-sql-" + System.nanoTime();
        Instant start = Instant.now();
        traceStore.addSpan(new SpanData(
                traceId,
                "root",
                null,
                "GET /masking-test/sql-fixture",
                Span.Kind.SERVER,
                start,
                start.plusMillis(50),
                Duration.ofMillis(50),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                CREATION_ORDER.incrementAndGet()));
        traceStore.addSpan(new SpanData(
                traceId,
                "db",
                "root",
                "query",
                Span.Kind.CLIENT,
                start.plusMillis(5),
                start.plusMillis(20),
                Duration.ofMillis(15),
                Map.of(
                        "db.system",
                        "h2",
                        "db.statement",
                        "INSERT INTO webhooks (callback_url) VALUES "
                                + "('https://admin:hunter2@internal.example.com/callback')"),
                List.of(),
                null,
                null,
                null,
                CREATION_ORDER.incrementAndGet()));

        JsonNode trace = traces.awaitTrace(traceId);

        assertThat(trace.path("queries")).hasSize(1);
        String sql = trace.path("queries").get(0).path("sql").asString();
        assertThat(sql)
                .isEqualTo("INSERT INTO webhooks (callback_url) VALUES "
                        + "('https://******@internal.example.com/callback')");
    }

    @TestConfiguration
    static class MaskingTestEndpoints {
        @Bean
        FixtureController maskingTestFixtureController() {
            return new FixtureController();
        }
    }

    @RestController
    static class FixtureController {

        @GetMapping("/masking-test/search")
        String search(@RequestParam(required = false) String api_key, @RequestParam(required = false) String q) {
            return "ok";
        }

        @PostMapping(value = "/masking-test/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        String login(@RequestParam String username, @RequestParam String password) {
            return "ok";
        }
    }
}
