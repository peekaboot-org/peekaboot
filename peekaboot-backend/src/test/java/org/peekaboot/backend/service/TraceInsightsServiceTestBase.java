package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.fail;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceListings;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * The store, the service on it and the fixtures that more than one of the
 * {@link TraceInsightsService} test classes needs.
 */
abstract class TraceInsightsServiceTestBase {

    protected InMemoryTraceStore store;
    protected TraceInsightsService service;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        service = newService(store);
    }

    protected static SpanData rootSpanWithoutTags(String traceId, String spanId, String name) {
        return span(spanId).in(traceId).named(name).build();
    }

    protected List<String> listedIds(TraceListings.Builder listing) {
        return idsOf(service.listTraces(listing.build()));
    }

    /** Follows {@code next} one row per page for ten pages at most, so a cursor that never runs out fails instead of hanging. */
    protected List<TraceResource> pagedOneByOne(TraceListings.Builder listing) {
        List<TraceResource> rows = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            TraceListResponse response =
                    service.listTraces(listing.limit(1).cursor(cursor).build());
            rows.addAll(response.traces());
            cursor = response.next();
            if (cursor == null) {
                return rows;
            }
        }
        return fail("next was still set after ten pages, with " + rows.size() + " rows so far");
    }

    protected static List<String> idsOf(TraceListResponse response) {
        return response.traces().stream().map(row -> row.summary().traceId()).toList();
    }

    protected TraceInsightsService newService(TraceStore store) {
        return newService(store, List.of(), List.of());
    }

    protected TraceInsightsService newService(
            TraceStore store, List<String> exclusions, List<String> applicationPackages) {
        return new TraceInsightsService(
                store,
                new MaskingEngine(),
                SqlFormatter.NONE,
                new PeekabootTracingProperties(),
                new StackTraceRules(exclusions, applicationPackages));
    }

    /** A service on the test's store that records every statement it lays out, in order. */
    protected TraceInsightsService serviceRecordingFormattedSqlInto(List<String> formatted) {
        SqlFormatter recording = sql -> {
            formatted.add(sql);
            return sql;
        };
        return new TraceInsightsService(
                store,
                new MaskingEngine(),
                recording,
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
    }

    /** A single SERVER root span of the given duration. */
    protected Spans.SpanBuilder rootSpan(String traceId, String name, Span.Kind kind, long durationMs) {
        return span("span-" + traceId).in(traceId).named(name).kind(kind).at(0, durationMs);
    }

    protected void addTrace(String traceId, long durationMs, boolean hasError) {
        Spans.SpanBuilder root = rootSpan(traceId, "test-operation", Span.Kind.SERVER, durationMs);
        if (hasError) {
            root.exception("TestException", "Test error");
        }
        store.addSpan(root.build());
    }

    /** A complete HTTP request as the OpenTelemetry convention tags its server span. */
    protected void addRequest(String traceId, String route, String path, int status, long startMs, long durationMs) {
        store.addSpan(span("span-" + traceId)
                .in(traceId)
                .named("http get " + route)
                .kind(Span.Kind.SERVER)
                .at(startMs, durationMs)
                .tag("http.request.method", "GET")
                .tag("http.route", route)
                .tag("url.path", path)
                .tag("http.response.status_code", String.valueOf(status))
                .build());
    }

    protected void addRequest(String traceId, int status) {
        addRequest(traceId, "/fixtures/{id}", "/fixtures/" + traceId, status, 0, 10);
    }

    protected void addConsumerTrace(String traceId, long durationMs) {
        store.addSpan(rootSpan(traceId, "receive message", Span.Kind.CONSUMER, durationMs)
                .build());
    }

    /**
     * The fragment an excluded request leaves behind. Peekaboot skips the root span of a
     * request on an excluded prefix - its own, the actuator's - so a connection acquired
     * while serving it is the only span stored under that trace id, and reads as the
     * bundle's root despite carrying a parent id that never arrives.
     */
    protected void addExcludedRequestFragment(String traceId) {
        store.addSpan(jdbcConnection("span-fragment-" + traceId)
                .in(traceId)
                .parent("root-span-never-exported")
                .build());
    }

    /** An HTTP request that dispatched background work which outlived it. */
    protected void addTraceWithAsyncWork(String traceId, long requestMs, long asyncMs) {
        store.addSpan(
                rootSpan(traceId, "test-operation", Span.Kind.SERVER, requestMs).build());
        store.addSpan(asyncTaskSpan(traceId, requestMs, asyncMs));
    }

    /** A request whose background task ran one statement and outlived it. */
    protected void addTraceWithAsyncQuery(String traceId, String sql) {
        store.addSpan(rootSpan(traceId, "test-operation", Span.Kind.SERVER, 50).build());
        store.addSpan(span("db-" + traceId)
                .in(traceId)
                .parent("async-" + traceId)
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(60, 20)
                .tags(Map.of("db.system", "postgresql", "db.statement", sql))
                .build());
        store.addSpan(asyncTask("async-" + traceId)
                .in(traceId)
                .parent("span-" + traceId)
                .at(40, 100)
                .build());
    }

    /** The span Peekaboot's decorator exports for one task, dispatched 10ms before the request ended. */
    protected static SpanData asyncTaskSpan(String traceId, long requestMs, long asyncMs) {
        return asyncTask("async-" + traceId)
                .in(traceId)
                .parent("span-" + traceId)
                .at(requestMs - 10, asyncMs)
                .build();
    }

    /**
     * A request slow enough to land in the SLOW bucket on its own synchronous work, which
     * dispatched two independent background tasks - so a type filter matching only the
     * async rows has two rows to count against the one trace they both belong to.
     */
    protected void addTraceWithTwoAsyncTasks(String traceId, long requestMs) {
        store.addSpan(
                rootSpan(traceId, "test-operation", Span.Kind.SERVER, requestMs).build());
        store.addSpan(asyncTask("async-a-" + traceId)
                .in(traceId)
                .parent("span-" + traceId)
                .tag("async.id", "a")
                .at(requestMs - 10, 240_000)
                .build());
        store.addSpan(asyncTask("async-b-" + traceId)
                .in(traceId)
                .parent("span-" + traceId)
                .tag("async.id", "b")
                .at(requestMs - 10, 5_000)
                .build());
    }
}
