package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
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
        // error trace
        Instant now = Instant.now();
        bucketStore.addSpan(new SpanData(
                "terr",
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
                bucketStore.nextCreationOrder()));
        // healthy trace
        bucketStore.addSpan(new SpanData(
                "tok",
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
                bucketStore.nextCreationOrder()));
        TraceInsightsService bucketService = newService(bucketStore);

        TraceInsightsResponse errors = bucketService.getInsights(10, TraceBucket.ERRORS, null, null);
        TraceInsightsResponse all = bucketService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(errors.traces()).extracting(TraceTree::traceId).containsExactly("terr");
        assertThat(all.traces()).hasSize(2);
    }

    @Test
    void responseCarriesBucketCounts() {
        InMemoryTraceStore bucketStore = new InMemoryTraceStore();
        Instant now = Instant.now();
        bucketStore.addSpan(new SpanData(
                "terr",
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
                bucketStore.nextCreationOrder()));
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

    @Test
    void getTraceInsights_shouldEnrichWithLogs() {
        // Given: a trace with an attached log
        addTrace("trace1", 100, false);
        store.addLog(new LogCapturedEvent(
                "trace1", "span-trace1", Instant.now(), "INFO", "TestLogger", "Test log message from trace", "main"));

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
        store.setRequest(new RequestCompletedEvent(
                "trace1",
                "GET",
                "/users",
                null,
                Map.of(),
                null,
                false,
                "UserController",
                "list",
                Map.of(),
                Map.of(),
                List.of(),
                200,
                Map.of(),
                100));

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

    private SpanData rootSpanWithoutTags(InMemoryTraceStore forStore, String traceId, String spanId, String name) {
        Instant now = Instant.now();
        return new SpanData(
                traceId,
                spanId,
                null,
                name,
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
                forStore.nextCreationOrder());
    }

    private TraceInsightsService newService(TraceStore store) {
        return new TraceInsightsService(store, traceTreeMapper, issueDetector, queryExtractor);
    }

    private void addTrace(String traceId, long durationMs, boolean hasError) {
        Instant start = Instant.EPOCH;
        Instant end = start.plusMillis(durationMs);

        SpanData span = new SpanData(
                traceId,
                "span-" + traceId,
                null,
                "test-operation",
                Span.Kind.SERVER,
                start,
                end,
                Duration.ofMillis(durationMs),
                Map.of(),
                List.of(),
                hasError ? "Test error" : null,
                hasError ? "TestException" : null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        store.addSpan(span);
    }

    private void addConsumerTrace(String traceId, long durationMs) {
        Instant start = Instant.EPOCH;
        SpanData span = new SpanData(
                traceId,
                "span-" + traceId,
                null,
                "receive message",
                Span.Kind.CONSUMER,
                start,
                start.plusMillis(durationMs),
                Duration.ofMillis(durationMs),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());
        store.addSpan(span);
    }

    private void addScheduledJobTrace(String traceId, long durationMs) {
        Instant start = Instant.EPOCH;
        SpanData span = new SpanData(
                traceId,
                "span-" + traceId,
                null,
                "task orderReconciler.reconcileOrders",
                null,
                start,
                start.plusMillis(durationMs),
                Duration.ofMillis(durationMs),
                Map.of("code.function", "reconcileOrders", "code.namespace", "org.peekaboot.example.OrderReconciler"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());
        store.addSpan(span);
    }

    private void addTraceWithOperation(String traceId, String operationName, long durationMs) {
        Instant start = Instant.EPOCH;
        SpanData span = new SpanData(
                traceId,
                "span-" + traceId,
                null,
                operationName,
                Span.Kind.SERVER,
                start,
                start.plusMillis(durationMs),
                Duration.ofMillis(durationMs),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());
        store.addSpan(span);
    }

    private void addTraceWithDbSpan(String traceId, long totalDurationMs) {
        Instant start = Instant.EPOCH;
        Instant dbSpanStart = start.plusMillis(10);

        // Root HTTP span
        SpanData rootSpan = new SpanData(
                traceId,
                "span-root-" + traceId,
                null,
                "GET /users/{id}",
                Span.Kind.SERVER,
                start,
                start.plusMillis(totalDurationMs),
                Duration.ofMillis(totalDurationMs),
                Map.of("http.method", "GET", "http.url", "/users/123"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        // DB query span
        SpanData dbSpan = new SpanData(
                traceId,
                "span-db-" + traceId,
                "span-root-" + traceId,
                "SELECT users",
                Span.Kind.CLIENT,
                dbSpanStart,
                dbSpanStart.plusMillis(50),
                Duration.ofMillis(50),
                Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users WHERE id = ?"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        store.addSpan(rootSpan);
        store.addSpan(dbSpan);
    }

    /** Same shape as {@link #addTraceWithDbSpan}, but the DB query span is captured twice -
     * a duplicate (extra {@code peer.service} tag, same name and other tags) arriving as a
     * direct child of the real span, added first, mirroring the OTel BatchSpanProcessor's
     * export ordering (a span cannot end, and so export, before the ancestor containing it). */
    private void addTraceWithDuplicatedDbSpan(String traceId, long totalDurationMs) {
        Instant start = Instant.EPOCH;
        Instant dbSpanStart = start.plusMillis(10);
        String dbSpanId = "span-db-" + traceId;

        SpanData rootSpan = new SpanData(
                traceId,
                "span-root-" + traceId,
                null,
                "GET /users/{id}",
                Span.Kind.SERVER,
                start,
                start.plusMillis(totalDurationMs),
                Duration.ofMillis(totalDurationMs),
                Map.of("http.method", "GET", "http.url", "/users/123"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        SpanData duplicate = new SpanData(
                traceId,
                "span-db-dup-" + traceId,
                dbSpanId,
                "SELECT users",
                Span.Kind.CLIENT,
                dbSpanStart,
                dbSpanStart.plusMillis(50),
                Duration.ofMillis(50),
                Map.of(
                        "db.system",
                        "postgresql",
                        "db.statement",
                        "SELECT * FROM users WHERE id = ?",
                        "peer.service",
                        "dataSource"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        SpanData dbSpan = new SpanData(
                traceId,
                dbSpanId,
                "span-root-" + traceId,
                "SELECT users",
                Span.Kind.CLIENT,
                dbSpanStart,
                dbSpanStart.plusMillis(50),
                Duration.ofMillis(50),
                Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users WHERE id = ?"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());

        store.addSpan(rootSpan);
        store.addSpan(duplicate);
        store.addSpan(dbSpan);
    }
}
