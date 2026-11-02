package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RouteListResponse;
import org.peekaboot.backend.domain.trace.RouteStats;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;

/** {@code GET /api/routes} over a real store. Each test pins what a route's row counts, and over which traces. */
class TraceRoutesTest {

    private final InMemoryTraceStore store = TraceStores.withDefaults();

    private final TraceInsightsService service = newService(store);

    private static TraceInsightsService newService(TraceStore store) {
        return new TraceInsightsService(
                store,
                new MaskingEngine(),
                SqlFormatter.NONE,
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
    }

    /** A complete HTTP request, a server root carrying the OpenTelemetry request tags. */
    private static Spans.SpanBuilder request(
            String traceId, String method, String route, int status, long startMs, long durationMs) {
        return span("root-" + traceId)
                .in(traceId)
                .named("http " + method.toLowerCase(Locale.ROOT) + " " + route)
                .kind(Span.Kind.SERVER)
                .at(startMs, durationMs)
                .tag("http.request.method", method)
                .tag("http.route", route)
                .tag("url.path", route)
                .tag("http.response.status_code", String.valueOf(status));
    }

    private void add(String traceId, String method, String route, int status, long durationMs) {
        store.addSpan(request(traceId, method, route, status, 0, durationMs).build());
    }

    /** A request whose only route tag is Spring's legacy {@code NOT_FOUND} placeholder. */
    private void addUnmatched(String traceId, String method) {
        store.addSpan(span("root-" + traceId)
                .in(traceId)
                .named("http " + method.toLowerCase(Locale.ROOT))
                .kind(Span.Kind.SERVER)
                .tag("method", method)
                .tag("uri", "NOT_FOUND")
                .tag("status", "404")
                .build());
    }

    private RouteStats routeOf(String method, String route) {
        return service.listRoutes().routes().stream()
                .filter(row -> Objects.equals(row.method(), method) && Objects.equals(row.route(), route))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + method + " " + route));
    }

    /** {@code /orders} holds the newer request, so only the route tie-break puts {@code /accounts} first. */
    @Test
    void groupsByMethodAndRouteBusiestFirst() {
        add("d1", "GET", "/accounts", 200, 10);
        add("d2", "GET", "/accounts", 200, 10);
        add("a1", "GET", "/persons/{id}", 200, 10);
        add("b1", "POST", "/persons/{id}", 200, 10);
        add("c1", "GET", "/orders", 200, 10);
        add("a2", "GET", "/persons/{id}", 200, 10);
        add("c2", "GET", "/orders", 200, 10);
        add("a3", "GET", "/persons/{id}", 200, 10);

        assertThat(service.listRoutes().routes())
                .extracting(RouteStats::method, RouteStats::route, RouteStats::count)
                .containsExactly(
                        tuple("GET", "/persons/{id}", 3),
                        tuple("GET", "/accounts", 2),
                        tuple("GET", "/orders", 2),
                        tuple("POST", "/persons/{id}", 1));
    }

    /** Nearest rank, the way the Insights stats compute it. An even count picks a real sample, never a mean of two. */
    @Test
    void percentilesAreNearestRankOverEveryRequest() {
        add("t1", "GET", "/r", 200, 40);
        add("t2", "GET", "/r", 200, 10);
        add("t3", "GET", "/r", 200, 30);
        add("t4", "GET", "/r", 200, 20);

        RouteStats route = routeOf("GET", "/r");

        assertThat(route.p50Ms()).isEqualTo(20);
        assertThat(route.p95Ms()).isEqualTo(40);
        assertThat(route.maxMs()).isEqualTo(40);
    }

    /** {@code ERROR} is the store's failure flag. A request that only logged an error counts, and a 4xx does not. */
    @Test
    void countsFailedRequestsAndNamesTheNewest() {
        add("ok1", "GET", "/r", 200, 10);
        store.addSpan(request("thrown", "GET", "/r", 200, 0, 10)
                .exception("java.lang.IllegalStateException", "boom")
                .build());
        add("missing", "GET", "/r", 404, 10);
        add("logged", "GET", "/r", 200, 10);
        store.addLog(log("logged")
                .inSpan("root-logged")
                .at("ERROR")
                .saying("gateway down")
                .build());
        add("ok2", "GET", "/r", 200, 10);

        RouteStats route = routeOf("GET", "/r");

        assertThat(route.count()).isEqualTo(5);
        assertThat(route.errorCount()).isEqualTo(2);
        assertThat(route.latestErrorTraceId()).isEqualTo("logged");
    }

    /** The newest request at or above the slow threshold, the Slow bucket's rule. */
    @Test
    void namesTheNewestSlowRequest() {
        long threshold = new PeekabootTracingProperties().getSlowTraceThresholdMs();
        add("slow1", "GET", "/r", 200, threshold + 500);
        add("quick1", "GET", "/r", 200, threshold - 1);
        add("slow2", "GET", "/r", 200, threshold);
        add("quick2", "GET", "/r", 200, 30);

        assertThat(routeOf("GET", "/r").latestSlowTraceId()).isEqualTo("slow2");
    }

    @Test
    void namesNoLatestWhereNoneFailedOrRanSlow() {
        add("t1", "GET", "/r", 200, 10);

        RouteStats route = routeOf("GET", "/r");

        assertThat(route.errorCount()).isZero();
        assertThat(route.latestErrorTraceId()).isNull();
        assertThat(route.latestSlowTraceId()).isNull();
    }

    /** Two VERY_SLOW spans in one request make one request with that issue. */
    @Test
    void countsRequestsPerIssueTypeNotIssues() {
        store.addSpan(span("first").in("twice").parent("root-twice").at(0, 600).build());
        store.addSpan(
                span("second").in("twice").parent("root-twice").at(600, 600).build());
        store.addSpan(request("twice", "GET", "/r", 200, 0, 1_250).build());
        add("once", "GET", "/r", 200, 150);

        Map<IssueType, Integer> issues = routeOf("GET", "/r").issueCounts();

        assertThat(issues).containsOnlyKeys(IssueType.values());
        assertThat(issues.get(IssueType.VERY_SLOW)).isEqualTo(1);
        assertThat(issues.get(IssueType.SLOW)).isEqualTo(1);
        assertThat(issues.get(IssueType.REPEATED_QUERY)).isZero();
    }

    /** An async task is part of the request that started it. It has no row of its own, and its failure fails the request. */
    @Test
    void anAsyncTaskCountsWithItsRequest() {
        store.addSpan(request("t1", "GET", "/r", 200, 0, 50).build());
        store.addSpan(asyncTask("async-t1")
                .in("t1")
                .parent("root-t1")
                .at(40, 100)
                .exception("java.lang.IllegalStateException", "task failed")
                .build());

        RouteStats route = routeOf("GET", "/r");

        assertThat(route.count()).isEqualTo(1);
        assertThat(route.errorCount()).isEqualTo(1);
    }

    /**
     * An outbound call that roots its own trace carries a method and a status like a request, but
     * answered none; upkeep, a job, a request still running and a log-only trace are no route's either.
     * A server root with no HTTP tag lists as an HTTP request but names none, so no route counts it.
     */
    @Test
    void countsCompleteHttpRequestsOnly() {
        add("request", "GET", "/r", 200, 10);
        store.addSpan(span("call-root")
                .in("call")
                .named("http get")
                .kind(Span.Kind.CLIENT)
                .tag("http.request.method", "GET")
                .tag("url.full", "https://partner.example/feed")
                .tag("http.response.status_code", "200")
                .build());
        store.addSpan(jdbcConnection("pool-root").in("pool").build());
        store.addSpan(span("job-root")
                .in("job")
                .named("task reconciler.run")
                .tags(Map.of("code.function", "run", "code.namespace", "com.acme.Reconciler"))
                .build());
        store.addSpan(span("child").in("inflight").parent("root-inflight").build());
        store.addLog(log("logonly").build());
        store.addSpan(span("bare-root").in("bare").kind(Span.Kind.SERVER).build());

        RouteListResponse routes = service.listRoutes();

        assertThat(routes.routes()).extracting(RouteStats::route).containsExactly("/r");
        assertThat(routes.window().traceCount()).isEqualTo(1);
    }

    @Test
    void requestsRecordedWithoutARouteGroupUnderANullRoute() {
        add("found", "GET", "/r", 200, 10);
        addUnmatched("lost1", "GET");
        addUnmatched("lostPost", "POST");
        addUnmatched("lost2", "GET");

        assertThat(service.listRoutes().routes())
                .extracting(RouteStats::method, RouteStats::route, RouteStats::count)
                .containsExactly(tuple("GET", null, 2), tuple("GET", "/r", 1), tuple("POST", null, 1));
    }

    @Test
    void statesTheWindowItCountedOver() {
        store.addSpan(request("a", "GET", "/r", 200, 5_000, 10).build());
        store.addSpan(request("b", "GET", "/r", 200, 0, 10).build());
        store.addSpan(request("c", "GET", "/s", 200, 2_000, 10).build());

        RouteListResponse.Window window = service.listRoutes().window();

        assertThat(window.from()).isEqualTo(Instant.EPOCH);
        assertThat(window.to()).isEqualTo(Instant.EPOCH.plusMillis(5_000));
        assertThat(window.traceCount()).isEqualTo(3);
    }

    @Test
    void anEmptyStoreAndTracingOffHaveNoRoutesAndAnEmptyWindow() {
        RouteListResponse empty = new RouteListResponse(new RouteListResponse.Window(null, null, 0), List.of());

        assertThat(service.listRoutes()).isEqualTo(empty);
        assertThat(newService(null).listRoutes()).isEqualTo(empty);
    }
}
