package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

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
/*
 * READ_WRITE on the shared store: setUp() clears the TraceStore of the app this class
 * shares with the other SharedToolbarTestConfig tests, so it must not overlap with a
 * class that is pinning its own traces in that store (they hold the READ side).
 */
@ResourceLock(value = "shared-toolbar-trace-store", mode = ResourceAccessMode.READ_WRITE)
class DashboardTraceViewIT {

    /** Creation orders for hand-built spans; only their per-trace ascending order matters. */
    private static final AtomicLong CREATION_ORDER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    private PeekabootApi api;
    private String testTraceId;
    private String testSpanId;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        traceStore.clear();

        api = new PeekabootApi(port);

        String testName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        testTraceId = String.format("%016x", testName.hashCode());
        testSpanId = String.format("%016x", (testName + "span").hashCode());

        injectTestSpan();
    }

    @Test
    void anInjectedTraceIsListedByTheDashboardApi() {
        JsonNode response = api.getJson("/peekaboot/api/traces/insights");
        JsonNode traces = response.get("traces");

        assertThat(traces).isNotNull();
        assertThat(traces.isArray()).isTrue();

        List<String> traceIds = new ArrayList<>();
        traces.forEach(t -> traceIds.add(t.get("traceId").asString()));
        assertThat(traceIds).containsExactly(testTraceId);
    }

    @Test
    void traceDetailsShouldContainSpans() {
        JsonNode trace = api.getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode rootSpan = trace.get("rootSpan");

        assertThat(rootSpan).as("Trace should contain rootSpan").isNotNull();
        assertThat(rootSpan.get("spanId").asText())
                .as("rootSpan should have correct spanId")
                .isEqualTo(testSpanId);
    }

    @Test
    void traceDetailsShouldContainHttpAttributes() {
        JsonNode trace = api.getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode rootTags = trace.get("rootSpan").get("tags");

        assertThat(rootTags.get("http.method").asString()).isEqualTo("GET");
        assertThat(rootTags.get("url.path").asString()).isEqualTo("/persons");
    }

    @Test
    void traceShouldContainDatabaseSpansWhenQueryExecuted() {
        JsonNode trace = api.getJson("/peekaboot/api/traces/{traceId}/insights", testTraceId);
        JsonNode summary = trace.get("summary");

        assertThat(summary).isNotNull();
        JsonNode queries = summary.get("queries");

        assertThat(queries).isNotNull();
        assertThat(queries.get("count").asInt(-1))
                .as("the single DB span injected by injectTestSpan() must be counted as exactly one query")
                .isEqualTo(1);
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
                CREATION_ORDER.incrementAndGet()));
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
                CREATION_ORDER.incrementAndGet()));

        JsonNode errors = api.getJson("/peekaboot/api/traces/insights?bucket=errors");
        JsonNode all = api.getJson("/peekaboot/api/traces/insights?bucket=all");

        assertThat(errors.get("traces")).hasSize(1);
        assertThat(errors.get("traces").get(0).get("traceId").asString()).isEqualTo("berr");
        assertThat(all.get("traces")).hasSize(3);
        assertThat(all.get("bucketCounts").get("all").asInt()).isEqualTo(3);
        assertThat(all.get("bucketCounts").get("errors").asInt()).isEqualTo(1);
        assertThat(all.get("bucketCounts").get("slow").asInt()).isZero();
    }

    @Test
    void featuresShouldIndicateTracingEnabled() {
        JsonNode features = api.getJson("/peekaboot/api/features");

        assertThat(features.get("tracing").asBoolean())
                .as("Tracing feature should be enabled")
                .isTrue();

        assertThat(features.get("devToolbar").asBoolean())
                .as("DevToolbar feature should be enabled")
                .isTrue();
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
                CREATION_ORDER.incrementAndGet());
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
                CREATION_ORDER.incrementAndGet());
        traceStore.addSpan(dbSpan);
    }
}
