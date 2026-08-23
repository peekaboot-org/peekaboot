package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Span;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.junit.jupiter.api.Test;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves two masking fixes (Fix round 1: C1, C2) end to end through the real HTTP API and
 * a real Spring context - a unit test on the mapper/filter alone would not catch either,
 * since both defects existed despite RequestCaptureFilter's header masking and
 * QueryExtractor's own unit tests being green: the bug was in the parts neither test
 * exercised (parameters/query string; a raw span embedded straight into TraceRawData
 * without going through TraceTreeMapper at all).
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RequestAndQueryMaskingIntegrationTest.MaskingTestEndpoints.class)
class RequestAndQueryMaskingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TraceStore traceStore;

    private TraceApiClient traces() {
        return new TraceApiClient(port, objectMapper);
    }

    private RestClient restClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * The fixture endpoints below are registered only for this test (never shipped in the
     * app's own controllers) purely to give a real HTTP request a secret-bearing query
     * parameter and a secret-bearing form field - mirroring
     * {@code PeekabootActuatorServiceTest}'s {@code ThrowingEndpointConfig} pattern for a
     * test-only Spring bean.
     */
    @Test
    void aSecretBearingQueryParameterComesBackMaskedFromTheRawTraceApi() {
        restClient().get().uri("/masking-test/search?api_key=AKIAABCDEFGHIJKLMNOP&q=widgets")
                .retrieve().toBodilessEntity();

        JsonNode listed = traces().awaitTraceInBucket("all", "/masking-test/search");
        JsonNode trace = fetchRawTrace(listed.path("traceId").asString());

        JsonNode queryParams = trace.path("httpExchange").path("request").path("params").path("query");
        assertThat(queryParams.path("api_key").get(0).asString()).isEqualTo("******");
        assertThat(queryParams.path("q").get(0).asString()).isEqualTo("widgets");
        assertThat(trace.path("httpExchange").path("request").path("query").asString())
                .isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void aSecretBearingFormFieldComesBackMaskedFromTheRawTraceApi() {
        restClient().post().uri("/masking-test/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=alice&password=hunter2")
                .retrieve().toBodilessEntity();

        JsonNode listed = traces().awaitTraceInBucket("all", "/masking-test/login");
        JsonNode trace = fetchRawTrace(listed.path("traceId").asString());

        JsonNode formParams = trace.path("httpExchange").path("request").path("params").path("form");
        assertThat(formParams.path("password").get(0).asString()).isEqualTo("******");
        assertThat(formParams.path("username").get(0).asString()).isEqualTo("alice");
    }

    /**
     * The captured span is written straight to the real TraceStore bean rather than
     * triggered through an actual JDBC call: this app's real JDBC instrumentation
     * (datasource-proxy + the OTel bridge) tags queries as "db.query.text" with bind
     * placeholders already substituted for literal values, which QueryExtractor.findSql
     * does not recognise at all (it looks for "db.statement" or "jdbc.query[...]") - a
     * pre-existing, unrelated gap, not something this fix round's C2 scope covers, noted
     * in the task report rather than silently fixed here. Using a directly-added SpanData
     * with a "db.statement" tag (the shape QueryExtractor does recognise, and the one
     * TraceRawServiceTest/DashboardTraceViewTest already use) isolates that from what
     * this test is actually proving: that TraceRawService's real HTTP-facing endpoint
     * masks the SQL it serves, not just the QueryExtractor unit in isolation.
     */
    @Test
    void sqlCarryingACredentialShapedValueComesBackMaskedFromTheRawTraceApi() {
        String traceId = "masking-test-sql-" + System.nanoTime();
        Instant start = Instant.now();
        traceStore.addSpan(new SpanData(
                traceId, "root", null, "GET /masking-test/sql-fixture", Span.Kind.SERVER,
                start, start.plusMillis(50), Duration.ofMillis(50),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                traceStore.nextCreationOrder()
        ));
        traceStore.addSpan(new SpanData(
                traceId, "db", "root", "query", Span.Kind.CLIENT,
                start.plusMillis(5), start.plusMillis(20), Duration.ofMillis(15),
                Map.of("db.system", "h2", "db.statement",
                        "INSERT INTO webhooks (callback_url) VALUES "
                                + "('https://admin:hunter2@internal.example.com/callback')"),
                List.of(), null, null, null, null, null, List.of(),
                traceStore.nextCreationOrder()
        ));

        JsonNode trace = fetchRawTrace(traceId);

        assertThat(trace.path("queries")).hasSize(1);
        String sql = trace.path("queries").get(0).path("sql").asString();
        assertThat(sql).isEqualTo("INSERT INTO webhooks (callback_url) VALUES "
                + "('https://******@internal.example.com/callback')");
    }

    private JsonNode fetchRawTrace(String traceId) {
        String body = restClient().get().uri("/peekaboot/api/traces/" + traceId + "/raw")
                .retrieve().body(String.class);
        return objectMapper.readTree(body);
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
