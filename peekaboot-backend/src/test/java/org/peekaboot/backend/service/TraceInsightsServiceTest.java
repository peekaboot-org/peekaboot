package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceStores;
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
        store = TraceStores.withDefaults();
        traceTreeMapper = new TraceTreeMapper(new MaskingEngine());
        issueDetector = new IssueDetector(new UiTracingProperties());
        queryExtractor = new QueryExtractor(new MaskingEngine());
        service = newService(store);
    }

    /**
     * The list's SLOW badge means "some span in this trace carries a SLOW or VERY_SLOW
     * issue" - a per-span judgement at the span thresholds, distinct from the Slow bucket,
     * which is about the trace's total duration.
     */
    @Test
    void aTraceIsFlaggedSlowWhenAnySpanCarriesASlowOrVerySlowIssue() {
        addTrace("fast", 50, false);
        addTrace("slow", 150, false);
        addTrace("very-slow", 500, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces())
                .extracting(TraceTree::traceId, TraceTree::slow)
                .containsExactlyInAnyOrder(tuple("fast", false), tuple("slow", true), tuple("very-slow", true));
    }

    @Test
    void theDetailFlagsSlowTheSameWayTheListDoes() {
        addTrace("slow", 150, false);
        addTrace("fast", 50, false);

        assertThat(service.getTraceInsights("slow").orElseThrow().slow()).isTrue();
        assertThat(service.getTraceInsights("fast").orElseThrow().slow()).isFalse();
    }

    @Test
    void anEmptyStoreYieldsAnEmptyTraceList() {
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).isEmpty();
    }

    @Test
    void tracingIsAvailableWhenTraceStoreIsPresent() {
        assertThat(service.isTracingAvailable()).isTrue();
    }

    @Test
    void tracingIsUnavailableWithoutTraceStore() {
        TraceInsightsService serviceWithNullStore = newService(null);

        assertThat(serviceWithNullStore.isTracingAvailable()).isFalse();
    }

    @Test
    void getInsightsQueriesRequestedBucket() {
        InMemoryTraceStore bucketStore = TraceStores.withDefaults();
        bucketStore.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
                .build());
        bucketStore.addSpan(span("s2").in("tok").build());
        TraceInsightsService bucketService = newService(bucketStore);

        TraceInsightsResponse errors = bucketService.getInsights(10, TraceBucket.ERRORS, null, null);
        TraceInsightsResponse all = bucketService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(errors.traces()).extracting(TraceTree::traceId).containsExactly("terr");
        assertThat(all.traces()).hasSize(2);
    }

    @Test
    void responseCarriesBucketCounts() {
        InMemoryTraceStore bucketStore = TraceStores.withDefaults();
        bucketStore.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
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
    void theDetailReturnsTheTransformedTrace() {
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
    void theDetailDetectsIssues() {
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
    void anUnknownTraceIdYieldsAnEmptyDetail() {
        // When
        Optional<TraceTree> result = service.getTraceInsights("unknown");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void theDetailIsEmptyWithoutATraceStore() {
        // Given
        TraceInsightsService serviceWithNullStore = newService(null);

        // When
        Optional<TraceTree> result = serviceWithNullStore.getTraceInsights("trace1");

        // Then
        assertThat(result).isEmpty();
    }

    /** The list feeds the Traces tab's log badges; the counts come from the logs the bundle already holds. */
    @Test
    void eachTracesLogsAreCountedByLevel() {
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
    void theDetailIsEnrichedWithLogs() {
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
    void theDetailCarriesEmptyLogsWhenNoneAreStored() {
        // Given: no logs stored for this trace
        addTrace("trace1", 100, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().logs()).isEmpty();
    }

    /** The list has no detail to carry, but its trees still say "none" as a list, never as null. */
    @Test
    void theListCarriesEmptyLogAndQueryListsRatherThanNulls() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces().getFirst().logs()).isEmpty();
        assertThat(response.traces().getFirst().queries()).isEmpty();
    }

    /** A log captured outside any span (no spanId in the MDC) is listed but attached to no span. */
    @Test
    void aLogWithoutASpanIdStaysInTheFlatListOnly() {
        addTrace("trace1", 100, false);
        store.addLog(new LogCapturedEvent("trace1", null, Instant.EPOCH, "INFO", "TestLogger", "spanless", "main"));

        TraceTree result = service.getTraceInsights("trace1").orElseThrow();

        assertThat(result.logs()).extracting(TraceLog::message).containsExactly("spanless");
        assertThat(result.rootSpan().logs()).isNull();
        assertThat(result.summary().logs()).isEqualTo(new TraceTabSummary.LogsSummary(1, 0, 0));
    }

    @Test
    void theDetailExtractsQueries() {
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
    void theDetailCarriesEmptyQueriesWhenThereAreNoDbSpans() {
        // Given: A trace without DB spans
        addTrace("trace1", 100, false);

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().queries()).isEmpty();
    }

    @Test
    void theListFiltersByRootActionType() {
        addTrace("trace1", 100, false); // SERVER kind, no tags -> HTTP_REQUEST (default)
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void anInvalidRootActionTypeFilterIsIgnored() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "not-a-real-type", null);

        // Invalid filter value is caught and ignored, so no filtering is applied
        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void theListFiltersByRootOperationWithAPartialCaseInsensitiveMatch() {
        addTraceWithOperation("trace1", "GET /api/Users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, "users");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void theListFiltersByMultipleCommaSeparatedRootActionTypes() {
        addTrace("trace1", 100, false); // SERVER kind -> HTTP_REQUEST
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER
        addScheduledJobTrace("trace3", 100); // scheduled-task tags -> SCHEDULED_JOB

        TraceInsightsResponse response =
                service.getInsights(10, TraceBucket.ALL, "http_request,message_consumer", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactlyInAnyOrder("trace1", "trace2");
    }

    @Test
    void invalidTypesInACommaSeparatedFilterAreIgnored() {
        addTrace("trace1", 100, false);
        addConsumerTrace("trace2", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request,bogus", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    /**
     * The Traces tab's default request: with no chip selected it sends an include-list of
     * every type except CONNECTION_POOL, so routine pool-maintenance traces stay out of
     * the list and the filtered counts until their own chip asks for them.
     */
    @Test
    void theDashboardsDefaultIncludeListLeavesConnectionPoolTracesOut() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response =
                service.getInsights(10, TraceBucket.ALL, everyTypeExcept(RootActionType.CONNECTION_POOL), null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("http1");
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(2, 0, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    @Test
    void theListFiltersToConnectionPoolTracesWhenTheirChipAsksForThem() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "connection_pool", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("pool1");
    }

    @Test
    void filteredBucketCountsAreReturnedWhileATypeFilterIsActive() {
        addTrace("trace1", 100, false); // HTTP_REQUEST, ok
        addTrace("trace2", 100, true); // HTTP_REQUEST, error
        addConsumerTrace("trace3", 100); // MESSAGE_CONSUMER, ok

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "http_request", null);

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 1, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(2, 1, 0));
    }

    @Test
    void filteredBucketCountsAreReturnedForARootOperationFilter() {
        addTraceWithOperation("trace1", "GET /api/users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, "users");

        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    /** Root-level filtering decides the page and the counts; the limit cuts the page, never the counts. */
    @Test
    void theFilteredListRespectsTheLimitWhileCountsSeeEveryMatch() {
        for (int i = 1; i <= 5; i++) {
            addTrace("t" + i, 100, false); // HTTP_REQUEST
        }
        addConsumerTrace("c1", 100); // MESSAGE_CONSUMER

        TraceInsightsResponse response = service.getInsights(2, TraceBucket.ALL, "http_request", null);

        assertThat(response.traces()).hasSize(2);
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(5, 0, 0));
    }

    @Test
    void filteredBucketCountsAreOmittedWithoutAFilter() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.filteredBucketCounts()).isNull();
    }

    @Test
    void theRootOperationFilterMatchesAFullyQualifiedTaskTarget() {
        addTraceWithOperation("trace1", "task scheduler.fixedDelay", 100);
        addTraceWithOperation("trace2", "task scheduler.fixedRate", 100);

        TraceInsightsResponse response =
                service.getInsights(10, TraceBucket.ALL, null, "org.peekaboot.example.Scheduler.fixedDelay");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    @Test
    void theDetailIsEnrichedWithTheHttpExchange() {
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
    void aLogEmittedInAFoldedDuplicateSpanIsAttachedToTheSurvivingSpan() {
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
    void theSameTreeIsReturnedWhetherOrNotTheDuplicateWasCaptured() {
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
    void theDetailSurfacesTheTruncatedFlagFromTheBundle() {
        InMemoryTraceStore cappedStore = new InMemoryTraceStore(100, 2);
        for (int i = 1; i <= 3; i++) {
            cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s" + i, "op" + i));
        }
        TraceInsightsService cappedService = newService(cappedStore);

        Optional<TraceTree> result = cappedService.getTraceInsights("t1");

        assertThat(result).isPresent();
        assertThat(result.get().truncated()).isTrue();
    }

    @Test
    void theListSurfacesTheTruncatedFlagToo() {
        InMemoryTraceStore cappedStore = new InMemoryTraceStore(100, 1);
        cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s1", "op1"));
        cappedStore.addSpan(rootSpanWithoutTags(cappedStore, "t1", "s2", "op2"));
        TraceInsightsService cappedService = newService(cappedStore);

        TraceInsightsResponse response = cappedService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).extracting(TraceTree::truncated).containsExactly(true);
    }

    private static SpanData rootSpanWithoutTags(
            InMemoryTraceStore forStore, String traceId, String spanId, String name) {
        return span(spanId).in(traceId).named(name).build();
    }

    private static LogCapturedEvent logAt(String traceId, String level) {
        return new LogCapturedEvent(
                traceId, "span-" + traceId, Instant.EPOCH, level, "TestLogger", level + " line", "main");
    }

    private TraceInsightsService newService(TraceStore store) {
        return new TraceInsightsService(store, traceTreeMapper, issueDetector, queryExtractor);
    }

    /** A single SERVER root span of the given duration. */
    private Spans.SpanBuilder rootSpan(String traceId, String name, Span.Kind kind, long durationMs) {
        return span("span-" + traceId).in(traceId).named(name).kind(kind).at(0, durationMs);
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

    /** The exact root datasource-micrometer exports for a standalone pool acquisition. */
    private void addConnectionPoolTrace(String traceId) {
        store.addSpan(rootSpan(traceId, "connection", Span.Kind.CLIENT, 30)
                .tags(Map.of(
                        "jdbc.datasource.name", "dataSource",
                        "jdbc.datasource.pool", "HikariPool-1"))
                .build());
    }

    private static String everyTypeExcept(RootActionType excluded) {
        return Arrays.stream(RootActionType.values())
                .filter(type -> type != excluded)
                .map(Enum::name)
                .collect(Collectors.joining(","));
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
