package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Span;
import java.util.List;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;

/**
 * The spans and traces more than one test writes straight to the store, each under the name of
 * what it stands for. A span comes back as its builder, so a caller adds the timing and whatever
 * else it asserts on. A trace comes back whole, in the order the store receives its spans.
 *
 * <p>Beside {@link TestSpans} for the same reason: the shapes are {@link SpanData}, which
 * peekaboot-test-support cannot name without depending on the backend that tests with it.
 */
public final class TraceShapes {

    private static final String ROOT = "root";
    private static final String HANDLER = "handler";
    private static final String HANDLER_NAME = "spring.handler";

    private TraceShapes() {}

    /** A request's root span, {@code root}: a server span named and tagged as a GET of {@code path}. */
    public static TestSpans.SpanBuilder httpRoot(String traceId, String path) {
        return TestSpans.span(traceId, ROOT)
                .named("http get " + path)
                .kind(Span.Kind.SERVER)
                .tag("http.method", "GET")
                .tag("url.path", path);
    }

    /** The controller method's span, {@code handler}. */
    public static TestSpans.SpanBuilder handler(String traceId, String parentId) {
        return TestSpans.span(traceId, HANDLER).parent(parentId).named(HANDLER_NAME);
    }

    /** The entry span of an {@code @Async} task, with the name and the tag the dashboard knows it by. */
    public static TestSpans.SpanBuilder asyncTask(String traceId, String spanId, String parentId) {
        return TestSpans.span(traceId, spanId)
                .parent(parentId)
                .named(AsyncTaskMarker.CONTEXTUAL_NAME)
                .tag(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE);
    }

    /** A query's span, which the caller names. */
    public static TestSpans.SpanBuilder query(
            String traceId, String spanId, String parentId, String dbSystem, String sql) {
        return TestSpans.span(traceId, spanId)
                .parent(parentId)
                .kind(Span.Kind.CLIENT)
                .tag("db.system.name", dbSystem)
                .tag("db.query.text", sql);
    }

    /** A query under a handler that was never exported: on its own, a trace that stays in progress. */
    public static SpanData orphanQuery(String traceId) {
        return TestSpans.span(traceId, "query")
                .parent("handler-never-exported")
                .named("SELECT person")
                .kind(Span.Kind.CLIENT)
                .at(5, 2)
                .build();
    }

    /**
     * The shape /orders produces: a query five levels down, under a nested HTTP call, and beside it
     * the result-set span that carries its row count.
     */
    public static List<SpanData> deepQuery(String traceId) {
        return List.of(
                httpRoot(traceId, "/orders").at(0, 40).build(),
                handler(traceId, ROOT).at(1, 38).build(),
                TestSpans.span(traceId, "client")
                        .parent(HANDLER)
                        .named("http get")
                        .kind(Span.Kind.CLIENT)
                        .at(2, 30)
                        .build(),
                TestSpans.span(traceId, "server")
                        .parent("client")
                        .named("http get /api/person/{id}")
                        .kind(Span.Kind.SERVER)
                        .at(3, 28)
                        .build(),
                TestSpans.span(traceId, "inner-handler")
                        .parent("server")
                        .named(HANDLER_NAME)
                        .at(4, 26)
                        .build(),
                query(traceId, "query", "inner-handler", "postgresql", "select * from customer_order")
                        .named("SELECT customer_order")
                        .at(5, 2)
                        .build(),
                TestSpans.span(traceId, "result-set")
                        .parent("inner-handler")
                        .named("result-set")
                        .kind(Span.Kind.CLIENT)
                        .at(7, 1)
                        .tag("jdbc.row-count", "8")
                        .build());
    }

    /** A request whose {@code @Async} task ran a query two levels under its entry span. */
    public static List<SpanData> asyncQuery(String traceId) {
        return List.of(
                httpRoot(traceId, "/orders/enrich").at(0, 20).build(),
                handler(traceId, ROOT).at(1, 18).build(),
                asyncTask(traceId, "async-task", HANDLER).at(5, 200).build(),
                TestSpans.span(traceId, "enrich")
                        .parent("async-task")
                        .named("enrich order")
                        .at(6, 190)
                        .build(),
                query(traceId, "async-query", "enrich", "h2", "select * from customer_order")
                        .named("SELECT customer_order")
                        .at(10, 5)
                        .build());
    }

    /**
     * A request that answers in 10ms and its {@code @Async} task, {@code async}, which works for
     * {@code asyncMs} outside the synchronous window the trace's total reads.
     */
    public static List<SpanData> slowAsyncUnderAShortRequest(String traceId, long asyncMs) {
        return List.of(
                httpRoot(traceId, "/orders/enrich").at(0, 10).build(),
                asyncTask(traceId, "async", ROOT).at(5, asyncMs).build());
    }

    /** A 10ms request and a 10ms call under it, {@code late}, which starts {@code startMs} in. */
    public static List<SpanData> lateCallUnderAShortRequest(String traceId, long startMs) {
        return List.of(
                httpRoot(traceId, "/api/person/all").at(0, 10).build(),
                TestSpans.span(traceId, "late")
                        .parent(ROOT)
                        .named("http get")
                        .kind(Span.Kind.CLIENT)
                        .at(startMs, 10)
                        .build());
    }
}
