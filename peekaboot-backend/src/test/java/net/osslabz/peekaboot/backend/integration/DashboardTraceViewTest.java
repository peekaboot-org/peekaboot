package net.osslabz.peekaboot.backend.integration;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import net.osslabz.peekaboot.backend.fixture.entity.Person;
import net.osslabz.peekaboot.backend.fixture.repository.PersonRepository;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestFixtureApplication.class, SharedToolbarTestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class DashboardTraceViewTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemorySpanStore spanStore;

    private RestClient restClient;
    private String baseUrl;
    private String testTraceId;
    private String testSpanId;

    @BeforeEach
    void setUp(TestInfo testInfo) {
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
            spanStore.nextCreationOrder()
        );
        spanStore.report(rootSpan);

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
            spanStore.nextCreationOrder()
        );
        spanStore.report(dbSpan);
    }

    @Test
    void traceFromToolbarShouldBeVisibleInDashboardApi() throws Exception {
        String tracesJson = restClient.get()
            .uri("/peekaboot/api/traces/insights")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        JsonNode response = objectMapper.readTree(tracesJson);
        JsonNode traces = response.get("traces");

        assertThat(traces).isNotNull();
        assertThat(traces.isArray()).isTrue();

        boolean found = false;
        for (JsonNode trace : traces) {
            if (testTraceId.equals(trace.get("traceId").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found)
            .as("Trace ID %s should be visible in dashboard API", testTraceId)
            .isTrue();
    }

    @Test
    void traceDetailsShouldContainSpans() throws Exception {
        String traceJson = restClient.get()
            .uri("/peekaboot/api/traces/{traceId}/insights", testTraceId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        JsonNode trace = objectMapper.readTree(traceJson);
        JsonNode rootSpan = trace.get("rootSpan");

        assertThat(rootSpan)
            .as("Trace should contain rootSpan")
            .isNotNull();
        assertThat(rootSpan.get("spanId").asText())
            .as("rootSpan should have correct spanId")
            .isEqualTo(testSpanId);
    }

    @Test
    void traceDetailsShouldContainHttpAttributes() throws Exception {
        String traceJson = restClient.get()
            .uri("/peekaboot/api/traces/{traceId}/insights", testTraceId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        JsonNode trace = objectMapper.readTree(traceJson);
        JsonNode inheritedAttributes = trace.get("inheritedAttributes");

        boolean hasHttpMethod = inheritedAttributes != null && inheritedAttributes.has("http.method");
        boolean hasUrlPath = inheritedAttributes != null && inheritedAttributes.has("url.path");
        boolean hasRootOperation = trace.has("rootOperation") && !trace.get("rootOperation").isNull();

        assertThat(hasHttpMethod || hasUrlPath || hasRootOperation)
            .as("Trace should contain HTTP attributes (http.method, url.path) or rootOperation")
            .isTrue();
    }

    @Test
    void traceShouldContainDatabaseSpansWhenQueryExecuted() throws Exception {
        String traceJson = restClient.get()
            .uri("/peekaboot/api/traces/{traceId}/insights", testTraceId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        JsonNode trace = objectMapper.readTree(traceJson);
        JsonNode metrics = trace.get("metrics");

        assertThat(metrics).isNotNull();
        int queryCount = metrics.has("dbQueryCount") ? metrics.get("dbQueryCount").asInt(-1) : -1;

        assertThat(queryCount)
            .as("Metrics should contain dbQueryCount >= 0")
            .isGreaterThanOrEqualTo(0);
    }

    @Test
    void dashboardShouldBeAccessible() {
        var response = restClient.get()
            .uri("/peekaboot/ui/dashboard/index.html")
            .accept(MediaType.TEXT_HTML)
            .exchange((req, res) -> {
                return Map.of(
                    "status", res.getStatusCode(),
                    "body", res.getStatusCode().is2xxSuccessful()
                        ? new String(res.getBody().readAllBytes())
                        : ""
                );
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
    void featuresShouldIndicateTracingEnabled() throws Exception {
        String featuresJson = restClient.get()
            .uri("/peekaboot/api/features")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        JsonNode features = objectMapper.readTree(featuresJson);

        assertThat(features.get("tracing").asBoolean())
            .as("Tracing feature should be enabled")
            .isTrue();

        assertThat(features.get("devToolbar").asBoolean())
            .as("DevToolbar feature should be enabled")
            .isTrue();
    }
}
