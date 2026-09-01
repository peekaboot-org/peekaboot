package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;

class TraceInsightsServiceTest {

    private InMemoryTraceStore store;
    private TraceTreeMapper traceTreeMapper;
    private IssueDetector issueDetector;
    private QueryExtractor queryExtractor;
    private TraceInsightsService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        traceTreeMapper = new TraceTreeMapper();
        issueDetector = new IssueDetector(new UiTracingProperties());
        queryExtractor = new QueryExtractor();
        service = newService(store);
    }

    @Test
    void getInsights_shouldTransformTracesAndCalculateSummary() {
        // Given: Two traces - one OK (100ms) and one with error (200ms)
        addTrace("trace1", 100, false);
        addTrace("trace2", 200, true);

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        // Then
        assertThat(response.traces()).hasSize(2);
        assertThat(response.summary().traceCount()).isEqualTo(2);
        assertThat(response.summary().errorCount()).isEqualTo(1);
        assertThat(response.summary().avgDurationMs()).isEqualTo(150.0);
    }

    @Test
    void getInsights_shouldCountSlowTraces() {
        // Given: Three traces - one slow (150ms > 100ms threshold)
        addTrace("fast", 50, false);
        addTrace("slow", 150, false);
        addTrace("normal", 80, false);

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        // Then: slowTrace has a span with 150ms duration, which triggers SLOW issue
        assertThat(response.summary().slowCount()).isEqualTo(1);
    }

    @Test
    void getInsights_shouldHandleEmptyTracesList() {
        // When
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        // Then
        assertThat(response.traces()).isEmpty();
        assertThat(response.summary().traceCount()).isEqualTo(0);
        assertThat(response.summary().errorCount()).isEqualTo(0);
        assertThat(response.summary().slowCount()).isEqualTo(0);
        assertThat(response.summary().avgDurationMs()).isEqualTo(0.0);
    }

    @Test
    void getInsights_shouldHandleNullTraceStore() {
        // Given: TraceStore is null (tracing not enabled)
        TraceInsightsService serviceWithNullStore = newService(null);

        // When
        TraceInsightsResponse response = serviceWithNullStore.getInsights(10, TraceBucket.ALL, null, null);

        // Then
        assertThat(response.traces()).isEmpty();
        assertThat(response.summary().traceCount()).isEqualTo(0);
    }

    @Test
    void isTracingAvailable_shouldBeTrueWhenTraceStoreIsPresent() {
        assertThat(service.isTracingAvailable()).isTrue();
    }

    @Test
    void isTracingAvailable_shouldBeFalseWithoutTraceStore() {
        TraceInsightsService serviceWithNullStore = newService(null);

        assertThat(serviceWithNullStore.isTracingAvailable()).isFalse();
    }

    @Test
    void getInsightsQueriesRequestedBucket() {
        InMemoryTraceStore bucketStore = new InMemoryTraceStore();
        bucketStore.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
                .order(bucketStore.nextCreationOrder())
                .build());
        bucketStore.addSpan(
                span("s2").in("tok").order(bucketStore.nextCreationOrder()).build());
        TraceInsightsService bucketService = newService(bucketStore);

        TraceInsightsResponse errors = bucketService.getInsights(10, TraceBucket.ERRORS, null, null);
        TraceInsightsResponse all = bucketService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(errors.traces()).extracting(TraceTree::traceId).containsExactly("terr");
        assertThat(all.traces()).hasSize(2);
    }

    @Test
    void responseCarriesBucketCounts() {
        InMemoryTraceStore bucketStore = new InMemoryTraceStore();
        bucketStore.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
                .order(bucketStore.nextCreationOrder())
                .build());
        TraceInsightsService bucketService = newService(bucketStore);

        TraceInsightsResponse response = bucketService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(1, 1, 0));
    }

    @Test
    void nullStoreYieldsEmptyResponseWithZeroCounts() {
        TraceInsightsService serviceWithNullStore = newService(null);

        TraceInsightsResponse response = serviceWithNullStore.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).isEmpty();
        assertThat(response.bucketCounts()).isEqualTo(BucketCounts.empty());
    }

    @Test
    void getTraceInsights_shouldReturnTransformedTrace() {
        // Given
        addTrace("trace1", 100, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("trace1");
        assertThat(result.get().durationMs()).isEqualTo(100L);
    }

    @Test
    void getTraceInsights_shouldDetectIssues() {
        // Given: A trace with a slow span (200ms > 100ms threshold)
        addTrace("trace1", 200, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().rootSpan().issues()).isNotEmpty();
        assertThat(result.get().rootSpan().issues()).extracting(SpanIssue::type).contains(IssueType.SLOW);
    }

    @Test
    void getTraceInsights_shouldReturnEmptyForUnknownTraceId() {
        // When
        Optional<TraceTree> result = service.getTraceInsights("unknown");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getTraceInsights_shouldHandleNullTraceStore() {
        // Given
        TraceInsightsService serviceWithNullStore = newService(null);

        // When
        Optional<TraceTree> result = serviceWithNullStore.getTraceInsights("trace1");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getInsights_shouldCalculateAverageDurationCorrectly() {
        // Given: Three traces with durations 100, 200, 300 -> avg = 200
        addTrace("trace1", 100, false);
        addTrace("trace2", 200, false);
        addTrace("trace3", 300, false);

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        // Then
        assertThat(response.summary().avgDurationMs()).isEqualTo(200.0);
    }

    @Test
    void getInsights_shouldCountTracesWithSlowOrVerySlowStatus() {
        // Given: one trace with VERY_SLOW status (500ms), one normal
        addTrace("slow", 500, false);
        addTrace("normal", 50, false);

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        // Then: slowTrace has VERY_SLOW issue, so slowCount should be 1
        assertThat(response.summary().slowCount()).isEqualTo(1);
    }

    /** The list feeds the Traces tab's log badges; the counts come from the logs the bundle already holds. */
    @Test
    void getInsights_shouldCountEachTracesLogsByLevel() {
        addTrace("trace1", 100, false);
        addTrace("trace2", 100, false);
        store.addLog(logAt("trace1", "ERROR"));
        store.addLog(logAt("trace1", "WARN"));
        store.addLog(logAt("trace1", "INFO"));

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces())
                .extracting(TraceTree::traceId, tree -> tree.summary().logs())
                .containsExactlyInAnyOrder(
                        tuple("trace1", new TraceTabSummary.LogsSummary(3, 1, 1)),
                        tuple("trace2", new TraceTabSummary.LogsSummary(0, 0, 0)));
    }

    @Test
    void getTraceInsights_shouldEnrichWithLogs() {
        // Given: a trace with an attached log
        addTrace("trace1", 100, false);
        store.addLog(new LogCapturedEvent(
                "trace1", "span-trace1", Instant.EPOCH, "INFO", "TestLogger", "Test log message from trace", "main"));

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().logs()).isNotNull();
        assertThat(result.get().logs()).hasSize(1);
        assertThat(result.get().logs().get(0).message()).isEqualTo("Test log message from trace");
        assertThat(result.get().logs().get(0).level()).isEqualTo("INFO");
        assertThat(result.get().logs().get(0).loggerName()).isEqualTo("TestLogger");
    }

    @Test
    void getTraceInsights_shouldReturnEmptyLogsWhenNoLogsStored() {
        // Given: no logs stored for this trace
        addTrace("trace1", 100, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().logs()).isNullOrEmpty();
    }

    @Test
    void getTraceInsights_shouldExtractQueries() {
        // Given: A trace with a DB span
        addTraceWithDbSpan("trace1", 100);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().queries()).isNotNull();
        assertThat(result.get().queries()).hasSize(1);
        assertThat(result.get().queries().get(0).sql()).isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(result.get().queries().get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(result.get().queries().get(0).durationMs()).isEqualTo(50L);
    }

    @Test
    void getTraceInsights_shouldReturnEmptyQueriesWhenNoDbSpans() {
        // Given: A trace without DB spans
        addTrace("trace1", 100, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().queries()).isNullOrEmpty();
    }

    @Test
    void getInsights_shouldFilterByRootActionType() {
        addTrace("trace1", 100, false); // SERVER kind, no tags -> HTTP_REQUEST (default)
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void getInsights_shouldIgnoreInvalidRootActionTypeFilter() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "not-a-real-type", null);

        // Invalid filter value is caught and ignored, so no filtering is applied
        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void getInsights_shouldFilterByRootOperationPartialCaseInsensitiveMatch() {
        addTraceWithOperation("trace1", "GET /api/Users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, "users");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void getInsights_shouldFilterByMultipleCommaSeparatedRootActionTypes() {
        addTrace("trace1", 100, false); // SERVER kind -> HTTP_REQUEST
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER
        addScheduledJobTrace("trace3", 100); // scheduled-task tags -> SCHEDULED_JOB

        TraceInsightsResponse response =
                service.getInsights(10, TraceBucket.ALL, "http_request,message_consumer", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactlyInAnyOrder("trace1", "trace2");
    }

    @Test
    void getInsights_shouldIgnoreInvalidTypesInCommaSeparatedFilter() {
        addTrace("trace1", 100, false);
        addConsumerTrace("trace2", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request,bogus", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void getInsights_shouldReturnFilteredBucketCountsWhenTypeFilterActive() {
        addTrace("trace1", 100, false); // HTTP_REQUEST, ok
        addTrace("trace2", 100, true); // HTTP_REQUEST, error
        addConsumerTrace("trace3", 100); // MESSAGE_CONSUMER, ok

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request", null);

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 1, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(2, 1, 0));
    }

    @Test
    void getInsights_shouldReturnFilteredBucketCountsForRootOperationFilter() {
        addTraceWithOperation("trace1", "GET /api/users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, "users");

        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    @Test
    void getInsights_shouldOmitFilteredBucketCountsWithoutFilter() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.filteredBucketCounts()).isNull();
    }

    @Test
    void getInsights_shouldMatchRootOperationByFullyQualifiedTaskTarget() {
        addTraceWithOperation("trace1", "task scheduler.fixedDelay", 100);
        addTraceWithOperation("trace2", "task scheduler.fixedRate", 100);

        TraceInsightsResponse response =
                service.getInsights(10, TraceBucket.ALL, null, "org.peekaboot.example.Scheduler.fixedDelay");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void getTraceInsights_shouldEnrichWithHttpExchange() {
        addTrace("trace1", 100, false);
        store.setRequest(RequestCompletedEvents.request("trace1")
                .path("/users")
                .controller("UserController", "list")
                .durationMs(100)
                .build());

        Optional<TraceTree> result = service.getTraceInsights("trace1");

        assertThat(result).isPresent();
        assertThat(result.get().httpExchange()).isNotNull();
        assertThat(result.get().httpExchange().request().method()).isEqualTo("GET");
        assertThat(result.get().httpExchange().response().status()).isEqualTo(200);
    }

    @Test
    void getTraceInsights_theDuplicatePairIsAlreadyCollapsedInTheStoreBeforeTheServiceRuns() {
        // Given: a DB span whose double-instrumented duplicate arrives first (as the OTel
        // BatchSpanProcessor's export ordering has it in production)
        addTraceWithDuplicatedDbSpan("trace1", 100);

        // Then: the store itself must already hold just the real span - TraceInsightsService
        // has no read-time dedup pass of its own to fall back on
        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.getTrace("trace1").get().spans()).hasSize(2); // root span + the real query span
    }

    @Test
    void getTraceInsights_attachesALogEmittedInAFoldedDuplicateSpanToTheSurvivingSpan() {
        // Given: a DB span whose duplicate is folded away on write, and a log emitted
        // while inside the folded-away duplicate's MDC scope - i.e. carrying the
        // duplicate's spanId, not the surviving span's
        addTraceWithDuplicatedDbSpan("trace1", 100);
        store.addLog(new LogCapturedEvent(
                "trace1", "span-db-dup-trace1", Instant.EPOCH, "TRACE", "TestLogger", "Datasource log", "main"));

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then: the log attaches to the surviving span in the tree rather than being
        // silently dropped as an orphan
        assertThat(result).isPresent();
        SpanNode dbSpanNode = result.get().rootSpan().children().stream()
                .filter(s -> "span-db-trace1".equals(s.spanId()))
                .findFirst()
                .orElseThrow();
        assertThat(dbSpanNode.logs()).extracting(TraceLog::message).containsExactly("Datasource log");

        // And: the flat logs list also carries the resolved (surviving) spanId
        assertThat(result.get().logs()).hasSize(1);
        assertThat(result.get().logs().get(0).spanId()).isEqualTo("span-db-trace1");
    }

    @Test
    void getTraceInsights_returnsTheSameTreeWhetherOrNotTheDuplicateWasCaptured() {
        // Given: one trace whose DB call was captured twice (real span + duplicate) and
        // an equivalent trace whose DB call was only ever captured once
        addTraceWithDuplicatedDbSpan("withDup", 100);
        addTraceWithDbSpan("withoutDup", 100);

        // When
        TraceTree withDup = service.getTraceInsights("withDup").orElseThrow();
        TraceTree withoutDup = service.getTraceInsights("withoutDup").orElseThrow();

        // Then: folding the duplicate away on write must produce the exact tree the
        // never-duplicated trace produces
        assertThat(withDup.summary().spans().count())
                .isEqualTo(withoutDup.summary().spans().count());
        assertThat(withDup.rootSpan().children()).hasSize(1);
        assertThat(withDup.queries()).hasSize(1);
        assertThat(withDup.queries().get(0).sql())
                .isEqualTo(withoutDup.queries().get(0).sql());
    }

    @Test
    void getTraceInsights_surfacesTheTruncatedFlagFromTheBundle() {
        InMemoryTraceStore cappedStore = new InMemoryTraceStore(100, 2, Duration.ofMinutes(5));
        for (int i = 1; i <= 3; i++) {
            cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s" + i, "op" + i));
        }
        TraceInsightsService cappedService = newService(cappedStore);

        Optional<TraceTree> result = cappedService.getTraceInsights("t1");

        assertThat(result).isPresent();
        assertThat(result.get().truncated()).isTrue();
    }

    @Test
    void getInsights_surfacesTheTruncatedFlagInTheListToo() {
        InMemoryTraceStore cappedStore = new InMemoryTraceStore(100, 1, Duration.ofMinutes(5));
        cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s1", "op1"));
        cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s2", "op2"));
        TraceInsightsService cappedService = newService(cappedStore);

        TraceInsightsResponse response = cappedService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).extracting(TraceTree::truncated).containsExactly(true);
    }

    private static SpanData rootSpanWithoutTags(
            InMemoryTraceStore forStore, String traceId, String spanId, String name) {
        return span(spanId)
                .in(traceId)
                .named(name)
                .order(forStore.nextCreationOrder())
                .build();
    }

    private static LogCapturedEvent logAt(String traceId, String level) {
        return new LogCapturedEvent(
                traceId, "span-" + traceId, Instant.EPOCH, level, "TestLogger", level + " line", "main");
    }

    private TraceInsightsService newService(TraceStore store) {
        return new TraceInsightsService(store, traceTreeMapper, issueDetector, queryExtractor);
    }

    /** A single SERVER root span of the given duration, numbered by the fixture store. */
    private Spans.SpanBuilder rootSpan(String traceId, String name, Span.Kind kind, long durationMs) {
        return span("span-" + traceId)
                .in(traceId)
                .named(name)
                .kind(kind)
                .at(0, durationMs)
                .order(store.nextCreationOrder());
    }

    private void addTrace(String traceId, long durationMs, boolean hasError) {
        Spans.SpanBuilder root = rootSpan(traceId, "test-operation", Span.Kind.SERVER, durationMs);
        if (hasError) {
            root.error("Test error", "TestException");
        }
        store.addSpan(root.build());
    }

    private void addConsumerTrace(String traceId, long durationMs) {
        store.addSpan(rootSpan(traceId, "receive message", Span.Kind.CONSUMER, durationMs)
                .build());
    }

    private void addScheduledJobTrace(String traceId, long durationMs) {
        store.addSpan(rootSpan(traceId, "task orderReconciler.reconcileOrders", null, durationMs)
                .tags(Map.of(
                        "code.function", "reconcileOrders",
                        "code.namespace", "org.peekaboot.example.OrderReconciler"))
                .build());
    }

    private void addTraceWithOperation(String traceId, String operationName, long durationMs) {
        store.addSpan(
                rootSpan(traceId, operationName, Span.Kind.SERVER, durationMs).build());
    }

    private SpanData httpRootSpan(String traceId, long totalDurationMs) {
        return span("span-root-" + traceId)
                .in(traceId)
                .named("GET /users/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, totalDurationMs)
                .tags(Map.of("http.method", "GET", "http.url", "/users/123"))
                .order(store.nextCreationOrder())
                .build();
    }

    /** A 50ms OpenTelemetry-convention query span, a child of {@link #httpRootSpan}. */
    private Spans.SpanBuilder dbSpan(String traceId, String spanId) {
        return span(spanId)
                .in(traceId)
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .tags(Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users WHERE id = ?"))
                .order(store.nextCreationOrder());
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
