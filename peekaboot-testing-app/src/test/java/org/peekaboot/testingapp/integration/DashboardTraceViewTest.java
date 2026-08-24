package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts on the dashboard's trace API against a {@link TraceStore} that holds only
 * what the test itself injects. Two things keep it that way: {@link #setUp} clears the
 * store before every single test, and {@link SharedToolbarTestConfig}'s stand-in
 * {@code Tracer} means the app's own request handling never produces a span for the
 * exporter to publish.
 */
@SpringBootTest(
        classes = {TestingApp.class, SharedToolbarTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DashboardTraceViewTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TraceStore traceStore;

    private RestClient restClient;
    private String baseUrl;
    private String testTraceId;
    private String testSpanId;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        traceStore.clear();

        baseUrl = "http://localhost:" + port;
        restClient = RestClient.builder().baseUrl(baseUrl).build();

        personRepository.deleteAll();
        Person person = new Person();
        person.setFirstName("Test");
        person.setLastName("User");
        person.setEmail("test@example.com");
        personRepository.save(person);

        String testName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        testTraceId = String.format("%016x", testName.hashCode());
        testSpanId = String.format("%016x", (testName + "span").hashCode());

        injectTestSpan();
    }

    @Test
    void traceFromToolbarShouldBeVisibleInDashboardApi() throws Exception {
        JsonNode response = getJson("/peekaboot/api/traces/insights");
        JsonNode traces = response.get("traces");

        assertThat(traces).isNotNull();
        assertThat(traces.isArray()).isTrue();

        List<String> traceIds = new ArrayList<>();
        traces.forEach(t -> traceIds.add(t.get("traceId").asString()));
        assertThat(traceIds).containsExactly(testTraceId);
    }

    @Test
    void traceDetailsShouldContainSpans() throws Exception {
        JsonNode trace = getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode rootSpan = trace.get("rootSpan");

        assertThat(rootSpan).as("Trace should contain rootSpan").isNotNull();
        assertThat(rootSpan.get("spanId").asText())
                .as("rootSpan should have correct spanId")
                .isEqualTo(testSpanId);
    }

    @Test
    void traceDetailsShouldContainHttpAttributes() throws Exception {
        JsonNode trace = getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode inheritedAttributes = trace.get("inheritedAttributes");

        boolean hasHttpMethod = inheritedAttributes != null && inheritedAttributes.has("http.method");
        boolean hasUrlPath = inheritedAttributes != null && inheritedAttributes.has("url.path");
        boolean hasRootOperation =
                trace.has("rootOperation") && !trace.get("rootOperation").isNull();

        assertThat(hasHttpMethod || hasUrlPath || hasRootOperation)
                .as("Trace should contain HTTP attributes (http.method, url.path) or rootOperation")
                .isTrue();
    }

    @Test
    void traceShouldContainDatabaseSpansWhenQueryExecuted() throws Exception {
        JsonNode trace = getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode summary = trace.get("summary");

        assertThat(summary).isNotNull();
        JsonNode queries = summary.get("queries");

        assertThat(queries).isNotNull();
        assertThat(queries.get("count").asInt(-1))
                .as("the single DB span injected by injectTestSpan() must be counted as exactly one query")
                .isEqualTo(1);
    }

    @Test
    void dashboardShouldBeAccessible() {
        Map<String, Object> response = restClient
                .get()
                .uri("/peekaboot/ui/dashboard/index.html")
                .accept(MediaType.TEXT_HTML)
                .exchange((req, res) -> {
                    return Map.of(
                            "status",
                            res.getStatusCode(),
                            "body",
                            res.getStatusCode().is2xxSuccessful()
                                    ? new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)
                                    : "");
                });

        HttpStatusCode status = (HttpStatusCode) response.get("status");
        String body = (String) response.get("body");

        if (status.is2xxSuccessful()) {
            assertThat(body)
                    .as("Dashboard HTML should load successfully")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("<!DOCTYPE html>");
        } else {
            assertThat(status.value())
                    .as("Dashboard endpoint should return 200 or 404 (if frontend not bundled)")
                    .isIn(200, 404);
        }
    }

    @Test
    void insightsEndpointFiltersByBucketAndReportsCounts() {
        Instant now = Instant.now();
        traceStore.addSpan(new SpanData(
                "berr",
                "s1",
                null,
                "op",
                null,
                now,
                now,
                Duration.ZERO,
                Map.of(),
                List.of(),
                "boom",
                "java.lang.RuntimeException",
                null,
                null,
                null,
                List.of(),
                traceStore.nextCreationOrder()));
        traceStore.addSpan(new SpanData(
                "bok",
                "s2",
                null,
                "op",
                null,
                now,
                now,
                Duration.ZERO,
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                traceStore.nextCreationOrder()));

        JsonNode errors = getJson("/peekaboot/api/traces/insights?bucket=errors");
        JsonNode all = getJson("/peekaboot/api/traces/insights?bucket=all");

        assertThat(errors.get("traces")).hasSize(1);
        assertThat(errors.get("traces").get(0).get("traceId").asString()).isEqualTo("berr");
        assertThat(all.get("traces")).hasSize(3);
        assertThat(all.get("bucketCounts").get("all").asInt()).isEqualTo(3);
        assertThat(all.get("bucketCounts").get("errors").asInt()).isEqualTo(1);
        assertThat(all.get("bucketCounts").get("slow").asInt()).isZero();
    }

    @Test
    void dashboardHtmlContainsBucketControl() {
        String html = getHtml("/peekaboot/ui/dashboard/index.html");

        assertThat(html).contains("id=\"traces-bucket\"");
        assertThat(html).contains("data-bucket=\"errors\"");
        assertThat(html).contains("data-bucket=\"slow\"");
    }

    @Test
    void featuresShouldIndicateTracingEnabled() throws Exception {
        JsonNode features = getJson("/peekaboot/api/features");

        assertThat(features.get("tracing").asBoolean())
                .as("Tracing feature should be enabled")
                .isTrue();

        assertThat(features.get("devToolbar").asBoolean())
                .as("DevToolbar feature should be enabled")
                .isTrue();
    }

    private JsonNode getJson(String path, Object... uriVariables) {
        String json = restClient
                .get()
                .uri(path, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(json);
    }

    private String getHtml(String path) {
        return restClient.get().uri(path).accept(MediaType.TEXT_HTML).retrieve().body(String.class);
    }

    private void injectTestSpan() {
        Instant start = Instant.now().minusMillis(100);
        Instant end = Instant.now();
        SpanData rootSpan = new SpanData(
                testTraceId,
                testSpanId,
                null,
                "GET /persons",
                Span.Kind.SERVER,
                start,
                end,
                Duration.between(start, end),
                Map.of("http.method", "GET", "url.path", "/persons"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                traceStore.nextCreationOrder());
        traceStore.addSpan(rootSpan);

        SpanData dbSpan = new SpanData(
                testTraceId,
                "db" + testSpanId,
                testSpanId,
                "SELECT * FROM person",
                Span.Kind.CLIENT,
                start.plusMillis(10),
                end.minusMillis(10),
                Duration.ofMillis(80),
                Map.of("db.system", "h2", "db.statement", "SELECT * FROM person"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                traceStore.nextCreationOrder());
        traceStore.addSpan(dbSpan);
    }
}
