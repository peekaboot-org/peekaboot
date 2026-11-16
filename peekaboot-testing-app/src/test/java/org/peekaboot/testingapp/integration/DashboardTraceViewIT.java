package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
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
class DashboardTraceViewIT extends TestingAppITBase {

    @Autowired
    private TraceStore traceStore;

    @Test
    void anInjectedTraceIsServedWithItsSpansAndTags() {
        String traceId = TestSpans.newTraceId();
        traceStore.addSpan(TraceShapes.httpRoot(traceId, "/persons").at(0, 100).build());
        traceStore.addSpan(TestSpans.span(traceId, "db")
                .parent("root")
                .named("SELECT * FROM person")
                .kind(Span.Kind.CLIENT)
                .at(10, 80)
                .tag("db.system", "h2")
                .tag("db.statement", "SELECT * FROM person")
                .build());

        JsonNode trace = api.getJson("/peekaboot/api/traces/{traceId}", traceId);

        assertThat(trace.path("data").path("rootSpan").path("spanId").asString())
                .isEqualTo("root");
        assertThat(trace.path("data")
                        .path("rootSpan")
                        .path("tags")
                        .path("http.method")
                        .asString())
                .isEqualTo("GET");
        assertThat(trace.path("data")
                        .path("rootSpan")
                        .path("tags")
                        .path("url.path")
                        .asString())
                .isEqualTo("/persons");
        assertThat(trace.path("summary")
                        .path("counts")
                        .path("queries")
                        .path("count")
                        .asInt(-1))
                .as("the one injected DB span is counted as exactly one query")
                .isEqualTo(1);
    }
}
