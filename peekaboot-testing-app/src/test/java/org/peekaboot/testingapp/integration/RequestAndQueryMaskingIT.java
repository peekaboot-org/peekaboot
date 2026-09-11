package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Proves request-parameter and SQL masking end to end through the real HTTP API and a
 * real Spring context - a unit test on the mapper/filter alone does not cover it:
 * RequestCaptureFilter's header masking and QueryExtractor's own unit tests can be green
 * while parameters and the query string leak, because neither exercises those parts.
 * The assertions run against {@code /api/traces/{traceId}/insights}, the only trace
 * endpoint, so it is the only path this masking has to hold on.
 *
 * <p>The secret-bearing endpoints it drives come from {@code MaskingFixtureController}, a bean
 * of {@code SharedFixturesConfig}, so this class shares the suite's context instead of forking
 * one for a fixture.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RequestAndQueryMaskingIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void aSecretBearingQueryParameterComesBackMaskedFromTheTraceInsightsApi() {
        String traceId = traces.get("/masking-test/search?api_key=AKIAABCDEFGHIJKLMNOP&q=widgets");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode queryParams =
                trace.path("httpExchange").path("request").path("params").path("query");
        assertThat(queryParams.path("api_key").get(0).asString()).isEqualTo("******");
        assertThat(queryParams.path("q").get(0).asString()).isEqualTo("widgets");
        assertThat(trace.path("httpExchange").path("request").path("query").asString())
                .isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void aSecretBearingFormFieldComesBackMaskedFromTheTraceInsightsApi() {
        ResponseEntity<Void> response = traces.restClient()
                .post()
                .uri("/masking-test/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=alice&password=hunter2")
                .retrieve()
                .toBodilessEntity();

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

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
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("GET /masking-test/sql-fixture")
                .kind(Span.Kind.SERVER)
                .at(0, 50)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "db")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(5, 15)
                .tag("db.system", "h2")
                .tag(
                        "db.statement",
                        "INSERT INTO webhooks (callback_url) VALUES "
                                + "('https://admin:hunter2@internal.example.com/callback')")
                .build());

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("queries")).hasSize(1);
        String sql = trace.path("queries").get(0).path("sql").asString();
        assertThat(sql)
                .isEqualTo("INSERT INTO webhooks (callback_url) VALUES "
                        + "('https://******@internal.example.com/callback')");
    }
}
