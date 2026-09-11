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
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * What the dashboard's trace endpoint makes of a trace written straight to the store: the
 * root span it hangs the tree from, the tags it carries through, and the query its child span
 * is counted as. Spans are injected rather than provoked so the shape under test is stated
 * outright; which tag a real driver populates is {@code QueryExtractorTest}'s question.
 *
 * <p>The store is shared with every other class running against this application, so the
 * trace is pinned by an id of this class's own and nothing here asserts a store-wide count.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DashboardTraceViewIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void anInjectedTraceIsServedWithItsSpansAndTags() {
        String traceId = "dashboard-trace-view-" + System.nanoTime();
        String rootSpanId = "root" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, rootSpanId)
                .named("GET /persons")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tag("http.method", "GET")
                .tag("url.path", "/persons")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "db" + rootSpanId)
                .parent(rootSpanId)
                .named("SELECT * FROM person")
                .kind(Span.Kind.CLIENT)
                .at(10, 80)
                .tag("db.system", "h2")
                .tag("db.statement", "SELECT * FROM person")
                .build());

        JsonNode trace = api.getJson("/peekaboot/api/traces/{traceId}/insights", traceId);

        assertThat(trace.path("rootSpan").path("spanId").asString()).isEqualTo(rootSpanId);
        assertThat(trace.path("rootSpan").path("tags").path("http.method").asString())
                .isEqualTo("GET");
        assertThat(trace.path("rootSpan").path("tags").path("url.path").asString())
                .isEqualTo("/persons");
        assertThat(trace.path("summary").path("queries").path("count").asInt(-1))
                .as("the one injected DB span is counted as exactly one query")
                .isEqualTo(1);
    }
}
