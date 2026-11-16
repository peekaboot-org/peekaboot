package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.HttpRequest;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import tools.jackson.databind.JsonNode;

/** The detail {@link TraceInsightsService#getTrace} serves for one trace. */
class TraceInsightsServiceDetailTest extends TraceInsightsServiceTestBase {

    @Test
    void theDetailGivesTheSummaryTheListGives() {
        addTrace("slow", new PeekabootTracingProperties().getSlowTraceThresholdMs(), true);

        TraceResource listed =
                service.listTraces(listing().limit(10).build()).traces().getFirst();
        TraceResource detail = service.getTrace("slow").orElseThrow();

        assertThat(detail.summary()).isEqualTo(listed.summary());
        assertThat(detail.summary().buckets()).containsExactly(TraceBucket.ERRORS, TraceBucket.SLOW);
    }

    /** The span tree and the query list serve one statement per query, formatted and masked once. */
    @Test
    void theDetailFormatsEachQueryOnce() {
        List<String> formatted = new ArrayList<>();
        TraceInsightsService recordingService = serviceRecordingFormattedSqlInto(formatted);
        addTraceWithDbSpan("t1", 100);

        TraceResource detail = recordingService.getTrace("t1").orElseThrow();

        assertThat(formatted).containsExactly("SELECT * FROM users WHERE id = ?");
        assertThat(detail.data().rootSpan().children().getFirst().statement().formatted())
                .isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(detail.data().queries().getFirst().statement())
                .isSameAs(detail.data().rootSpan().children().getFirst().statement());
    }

    @Test
    void theDetailReturnsTheTransformedTrace() {
        addTrace("trace1", 100, false);
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.summary().traceId()).isEqualTo("trace1");
        assertThat(result.summary().durationMs()).isEqualTo(100L);
    }

    @Test
    void theDetailDetectsIssues() {
        // A trace with a slow span (200ms > 100ms threshold)
        addTrace("trace1", 200, false);
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.data().rootSpan().issues()).isNotEmpty();
        assertThat(result.data().rootSpan().issues())
                .extracting(TraceIssue::type)
                .contains(IssueType.SLOW);
    }

    @Test
    void anUnknownTraceIdYieldsAnEmptyDetail() {
        Optional<TraceResource> result = service.getTrace("unknown");
        assertThat(result).isEmpty();
    }

    @Test
    void theDetailIsEmptyWithoutATraceStore() {
        TraceInsightsService serviceWithNullStore = newService(null);
        Optional<TraceResource> result = serviceWithNullStore.getTrace("trace1");
        assertThat(result).isEmpty();
    }

    @Test
    void theDetailIsEnrichedWithLogs() {
        // a trace with an attached log
        addTrace("trace1", 100, false);
        store.addLog(log("trace1")
                .inSpan("span-trace1")
                .saying("Test log message from trace")
                .build());
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.data().logs()).hasSize(1);
        assertThat(result.data().logs().get(0).message()).isEqualTo("Test log message from trace");
        assertThat(result.data().logs().get(0).level()).isEqualTo("INFO");
    }

    /** The service folds with the rules it was built with; {@link TraceLogMapperTest} pins the folding itself. */
    @Test
    void theDetailFoldsAStackTraceWithTheServicesRules() {
        String trace = """
                java.lang.IllegalStateException: boom
                \tat org.peekaboot.app.Thing.method(Thing.java:10)
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)
                """;
        TraceInsightsService folding = newService(store, List.of("org.springframework"), List.of("org.peekaboot.app"));
        addTrace("trace1", 100, false);
        store.addLog(log("trace1").inSpan("span-trace1").withStackTrace(trace).build());

        TraceLog result = folding.getTrace("trace1").orElseThrow().data().logs().getFirst();

        assertThat(result.applicationFrames()).containsExactly(new StackTraceFolding.Range(1, 2));
        assertThat(result.hiddenFrames()).containsExactly(new StackTraceFolding.Range(2, 3));
    }

    /** The span tree shows a log count, which the browser takes from the flat list, so a span carries no copy. */
    @Test
    void theDetailServesEachLogOnceInItsFlatList() {
        addTrace("trace1", 100, false);
        store.addLog(log("trace1").inSpan("span-trace1").saying("once").build());

        JsonNode json =
                PeekabootJson.MAPPER.valueToTree(service.getTrace("trace1").orElseThrow());

        assertThat(json.path("data").path("logs").size()).isEqualTo(1);
        assertThat(json.path("data").path("rootSpan").has("logs")).isFalse();
    }

    /**
     * An {@code @Async} task can log while the detail is being built, so the badge counts and the
     * list must come from one read of the bundle's logs. The task logs every few microseconds
     * throughout, so a detail that read them twice disagrees with itself on about every other
     * request. The first detail runs before the task starts, so the race is not spent loading
     * classes.
     */
    @Test
    void theDetailsLogCountMatchesItsLogListWhileATaskKeepsLogging() throws Exception {
        InMemoryTraceStore loggingStore = TraceStores.with(p -> p.setMaxLogsPerTrace(Integer.MAX_VALUE));
        loggingStore.addSpan(
                rootSpan("trace1", "test-operation", Span.Kind.SERVER, 100).build());
        TraceInsightsService loggingService = newService(loggingStore);
        loggingService.getTrace("trace1");
        Thread task = new Thread(
                () -> {
                    for (int i = 0; i < 5_000; i++) {
                        loggingStore.addLog(log("trace1").inSpan("span-trace1").build());
                        LockSupport.parkNanos(10_000);
                    }
                },
                "async-task");
        int detailsWhileLogging = 0;
        int disagreeing = 0;
        task.start();
        while (task.isAlive()) {
            TraceResource detail = loggingService.getTrace("trace1").orElseThrow();
            if (detail.summary().counts().logs().count() != detail.data().logs().size()) {
                disagreeing++;
            }
            if (task.isAlive()) {
                detailsWhileLogging++;
            }
        }
        assertThat(task.join(Duration.ofSeconds(5))).isTrue();

        assertThat(detailsWhileLogging).isPositive();
        assertThat(disagreeing)
                .as("details out of %s built while the task logged", detailsWhileLogging)
                .isZero();
    }

    @Test
    void theDetailCarriesEmptyLogsWhenNoneAreStored() {
        // no logs stored for this trace
        addTrace("trace1", 100, false);
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.data().logs()).isEmpty();
    }

    /** A log captured outside any span (no spanId in the MDC) is listed but attached to no span. */
    @Test
    void aLogWithoutASpanIdStaysInTheFlatListOnly() {
        addTrace("trace1", 100, false);
        store.addLog(log("trace1").inSpan(null).saying("spanless").build());

        TraceResource result = service.getTrace("trace1").orElseThrow();

        assertThat(result.data().logs()).extracting(TraceLog::message).containsExactly("spanless");
        assertThat(result.data().logs().getFirst().spanId()).isNull();
        assertThat(result.summary().counts().logs()).isEqualTo(new TraceSummary.LogCounts(1, 0, 0));
    }

    @Test
    void theDetailExtractsQueries() {
        // A trace with a DB span
        addTraceWithDbSpan("trace1", 100);
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.data().queries()).hasSize(1);
        assertThat(result.data().queries().get(0).spanId()).isEqualTo("span-db-trace1");
        assertThat(result.data().queries().get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(result.data().queries().get(0).durationMs()).isEqualTo(50L);
    }

    @Test
    void theDetailCarriesEmptyQueriesWhenThereAreNoDbSpans() {
        // A trace without DB spans
        addTrace("trace1", 100, false);
        TraceResource result = service.getTrace("trace1").orElseThrow();
        assertThat(result.data().queries()).isEmpty();
    }

    @Test
    void theDetailIsEnrichedWithTheHttpExchange() {
        addTrace("trace1", 100, false);
        store.setRequest(RequestCompletedEvents.request("trace1")
                .path("/users")
                .controller("UserController", "list")
                .build());

        Optional<TraceResource> result = service.getTrace("trace1");

        HttpExchange exchange = result.orElseThrow().data().httpExchange();
        assertThat(exchange.request().path()).isEqualTo("/users");
        assertThat(exchange.request().controller()).isEqualTo(new HttpRequest.Controller("UserController", "list"));
        assertThat(exchange.response().status()).isEqualTo(200);
    }

    @Test
    void theSameTreeIsReturnedWhetherOrNotTheDuplicateWasCaptured() {
        // one trace whose DB call was captured twice (real span + duplicate) and
        // an equivalent trace whose DB call was only ever captured once
        addTraceWithDuplicatedDbSpan("withDup", 100);
        addTraceWithDbSpan("withoutDup", 100);
        TraceResource withDup = service.getTrace("withDup").orElseThrow();
        TraceResource withoutDup = service.getTrace("withoutDup").orElseThrow();

        // folding the duplicate away on write must produce the exact tree the
        // never-duplicated trace produces
        assertThat(withDup.summary().counts().spans())
                .isEqualTo(withoutDup.summary().counts().spans());
        assertThat(withDup.data().rootSpan().children()).hasSize(1);
        assertThat(withDup.data().queries()).hasSize(1);
        assertThat(withDup.data().rootSpan().children().get(0).statement())
                .isNotNull()
                .isEqualTo(withoutDup.data().rootSpan().children().get(0).statement());
    }

    @Test
    void theDetailCountsTheSpansTheCapDropped() {
        InMemoryTraceStore cappedStore = TraceStores.with(p -> p.setMaxSpansPerTrace(2));
        for (int i = 1; i <= 3; i++) {
            cappedStore.addSpan(rootSpanWithoutTags("t1", "s" + i, "op" + i));
        }
        TraceInsightsService cappedService = newService(cappedStore);

        TraceResource result = cappedService.getTrace("t1").orElseThrow();

        assertThat(result.summary().counts().droppedSpans()).isEqualTo(1);
    }

    /**
     * A failing root that lands right after the bucket read must not make the trace say more than its
     * buckets do. The store below lands it there, inside {@code bucketsOf}, so the order of the two reads
     * decides what the response says.
     */
    @Test
    void aTraceIsMappedBeforeItsBucketsAreRead() {
        InMemoryTraceStore landing = TraceStores.landingAfterTheFirstBucketRead(span("root")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(0, 20)
                .exception("IllegalStateException", "boom")
                .build());
        landing.addSpan(span("child").in("t1").parent("root").at(10, 5).build());

        TraceSummary summary = newService(landing).getTrace("t1").orElseThrow().summary();

        assertThat(summary.outcome()).isEqualTo(TraceOutcome.IN_PROGRESS);
        assertThat(summary.buckets()).isEmpty();
    }

    @Test
    void anInterruptedTraceSaysSoAndNamesItsRun() {
        Instant earlierRun = Instant.parse("2026-10-30T08:00:00Z");
        store.beginRun(earlierRun);
        store.addSpan(span("query").in("cut-off").parent("handler").at(0, 5).build());
        store.beginRun(null);

        TraceSummary summary = service.getTrace("cut-off").orElseThrow().summary();

        assertThat(summary.outcome()).isEqualTo(TraceOutcome.INTERRUPTED);
        assertThat(summary.complete()).isFalse();
        assertThat(summary.run()).isEqualTo(earlierRun);
    }

    private SpanData httpRootSpan(String traceId, long totalDurationMs) {
        return span("span-root-" + traceId)
                .in(traceId)
                .named("GET /users/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, totalDurationMs)
                .tags(Map.of("http.method", "GET", "http.url", "/users/123"))
                .build();
    }

    /** A 50ms OpenTelemetry-convention query span, a child of {@link #httpRootSpan}. */
    private Spans.SpanBuilder dbSpan(String traceId, String spanId) {
        return span(spanId)
                .in(traceId)
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .tags(Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users WHERE id = ?"));
    }

    private void addTraceWithDbSpan(String traceId, long totalDurationMs) {
        store.addSpan(httpRootSpan(traceId, totalDurationMs));
        store.addSpan(dbSpan(traceId, "span-db-" + traceId)
                .parent("span-root-" + traceId)
                .build());
    }

    /** Same shape as {@link #addTraceWithDbSpan}, but the DB query span is captured twice -
     * a duplicate (extra {@code peer.service} tag, same name and other tags) arriving as a
     * direct child of the real span, added first, mirroring the OTel BatchSpanProcessor's
     * export ordering (a span cannot end, and so export, before the ancestor containing it). */
    private void addTraceWithDuplicatedDbSpan(String traceId, long totalDurationMs) {
        String dbSpanId = "span-db-" + traceId;
        SpanData rootSpan = httpRootSpan(traceId, totalDurationMs);
        SpanData duplicate = dbSpan(traceId, "span-db-dup-" + traceId)
                .parent(dbSpanId)
                .tag("peer.service", "dataSource")
                .build();
        SpanData realDbSpan =
                dbSpan(traceId, dbSpanId).parent("span-root-" + traceId).build();

        store.addSpan(rootSpan);
        store.addSpan(duplicate);
        store.addSpan(realDbSpan);
    }
}
