package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.HttpRequest;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;
import tools.jackson.databind.JsonNode;

class TraceInsightsServiceTest {

    private InMemoryTraceStore store;
    private TraceTreeMapper traceTreeMapper;
    private IssueDetector issueDetector;
    private QueryExtractor queryExtractor;
    private TraceInsightsService service;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        traceTreeMapper = new TraceTreeMapper(new MaskingEngine(), SqlFormatter.NONE);
        issueDetector = new IssueDetector(new UiTracingProperties());
        queryExtractor = new QueryExtractor();
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

    /** The span tree and the Queries list carry one statement per query, formatted and masked once. */
    @Test
    void theDetailFormatsEachQueryOnceForTheTreeAndTheQueriesList() {
        List<String> formatted = new ArrayList<>();
        SqlFormatter recording = sql -> {
            formatted.add(sql);
            return sql;
        };
        TraceInsightsService recordingService = new TraceInsightsService(
                store,
                new TraceTreeMapper(new MaskingEngine(), recording),
                issueDetector,
                queryExtractor,
                new StackTraceRules(List.of(), List.of()));
        addTraceWithDbSpan("t1", 100);

        TraceTree detail = recordingService.getTraceInsights("t1").orElseThrow();

        assertThat(formatted).containsExactly("SELECT * FROM users WHERE id = ?");
        assertThat(detail.queries().getFirst().statement())
                .isEqualTo(detail.rootSpan().children().getFirst().query());
    }

    @Test
    void anEmptyStoreYieldsAnEmptyTraceList() {
        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).isEmpty();
    }

    @Test
    void tracingIsAvailableExactlyWhenThereIsATraceStore() {
        assertThat(service.isTracingAvailable()).isTrue();
        assertThat(newService(null).isTracingAvailable()).isFalse();
    }

    @Test
    void getInsightsQueriesRequestedBucket() {
        store.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
                .build());
        store.addSpan(span("s2").in("tok").build());

        TraceInsightsResponse errors = service.getInsights(10, TraceBucket.ERRORS, null, null);
        TraceInsightsResponse all = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(errors.traces()).extracting(TraceTree::traceId).containsExactly("terr");
        assertThat(all.traces()).hasSize(2);
    }

    @Test
    void responseCarriesBucketCounts() {
        store.addSpan(span("s1")
                .in("terr")
                .error("boom", "java.lang.RuntimeException")
                .build());

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

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
        addTrace("trace1", 100, false);
        Optional<TraceTree> result = service.getTraceInsights("trace1");
        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("trace1");
        assertThat(result.get().durationMs()).isEqualTo(100L);
    }

    @Test
    void theDetailDetectsIssues() {
        // A trace with a slow span (200ms > 100ms threshold)
        addTrace("trace1", 200, false);
        Optional<TraceTree> result = service.getTraceInsights("trace1");
        assertThat(result).isPresent();
        assertThat(result.get().rootSpan().issues()).isNotEmpty();
        assertThat(result.get().rootSpan().issues()).extracting(SpanIssue::type).contains(IssueType.SLOW);
    }

    @Test
    void anUnknownTraceIdYieldsAnEmptyDetail() {
        Optional<TraceTree> result = service.getTraceInsights("unknown");
        assertThat(result).isEmpty();
    }

    @Test
    void theDetailIsEmptyWithoutATraceStore() {
        TraceInsightsService serviceWithNullStore = newService(null);
        Optional<TraceTree> result = serviceWithNullStore.getTraceInsights("trace1");
        assertThat(result).isEmpty();
    }

    /** The list feeds the Traces tab's log badges; the counts come from the logs the bundle already holds. */
    @Test
    void eachTracesLogsAreCountedByLevel() {
        addTrace("trace1", 100, false);
        addTrace("trace2", 100, false);
        for (String level : List.of("ERROR", "WARN", "INFO")) {
            store.addLog(log("trace1").inSpan("span-trace1").at(level).build());
        }

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces())
                .extracting(TraceTree::traceId, tree -> tree.summary().logs())
                .containsExactlyInAnyOrder(
                        tuple("trace1", new TraceTabSummary.LogsSummary(3, 1, 1)),
                        tuple("trace2", new TraceTabSummary.LogsSummary(0, 0, 0)));
    }

    @Test
    void theDetailIsEnrichedWithLogs() {
        // a trace with an attached log
        addTrace("trace1", 100, false);
        store.addLog(log("trace1")
                .inSpan("span-trace1")
                .saying("Test log message from trace")
                .build());
        Optional<TraceTree> result = service.getTraceInsights("trace1");
        assertThat(result).isPresent();
        assertThat(result.get().logs()).hasSize(1);
        assertThat(result.get().logs().get(0).message()).isEqualTo("Test log message from trace");
        assertThat(result.get().logs().get(0).level()).isEqualTo("INFO");
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

        TraceLog result =
                folding.getTraceInsights("trace1").orElseThrow().logs().getFirst();

        assertThat(result.applicationFrames()).containsExactly(new StackTraceFolding.Range(1, 2));
        assertThat(result.hiddenFrames()).containsExactly(new StackTraceFolding.Range(2, 3));
    }

    /** The span tree shows a log count, which the browser takes from the flat list, so a span carries no copy. */
    @Test
    void theDetailServesEachLogOnceInItsFlatList() {
        addTrace("trace1", 100, false);
        store.addLog(log("trace1").inSpan("span-trace1").saying("once").build());

        JsonNode json = PeekabootJson.MAPPER.valueToTree(
                service.getTraceInsights("trace1").orElseThrow());

        assertThat(json.path("logs").size()).isEqualTo(1);
        assertThat(json.path("rootSpan").has("logs")).isFalse();
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
        loggingService.getTraceInsights("trace1");
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
            TraceTree detail = loggingService.getTraceInsights("trace1").orElseThrow();
            if (detail.summary().logs().count() != detail.logs().size()) {
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
        Optional<TraceTree> result = service.getTraceInsights("trace1");
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
        store.addLog(log("trace1").inSpan(null).saying("spanless").build());

        TraceTree result = service.getTraceInsights("trace1").orElseThrow();

        assertThat(result.logs()).extracting(TraceLog::message).containsExactly("spanless");
        assertThat(result.logs().getFirst().spanId()).isNull();
        assertThat(result.summary().logs()).isEqualTo(new TraceTabSummary.LogsSummary(1, 0, 0));
    }

    @Test
    void theDetailExtractsQueries() {
        // A trace with a DB span
        addTraceWithDbSpan("trace1", 100);
        Optional<TraceTree> result = service.getTraceInsights("trace1");
        assertThat(result).isPresent();
        assertThat(result.get().queries()).hasSize(1);
        assertThat(result.get().queries().get(0).statement().text()).isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(result.get().queries().get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(result.get().queries().get(0).durationMs()).isEqualTo(50L);
    }

    @Test
    void theDetailCarriesEmptyQueriesWhenThereAreNoDbSpans() {
        // A trace without DB spans
        addTrace("trace1", 100, false);
        Optional<TraceTree> result = service.getTraceInsights("trace1");
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

    /** A filter naming nothing recognizable falls back to the default view, not to no filter at all. */
    @Test
    void aRootActionTypeFilterWithNoRecognizedTypeFallsBackToTheDefaultView() {
        addTrace("trace1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "not-a-real-type", null);

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
     * The default view: a request that names no type gets every type except
     * MAINTENANCE, so those traces stay out of the list and out of the filtered
     * counts until something asks for them by name.
     */
    @Test
    void aRequestNamingNoTypeLeavesMaintenanceTracesOut() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("http1");
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(2, 0, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    /** The counting pass over the buckets a request did not ask for runs against Slow too, and only a slow match counts. */
    @Test
    void theSlowBucketIsFilteredAndCountedLikeTheOthers() {
        InMemoryTraceStore slowStore = TraceStores.with(p -> p.setSlowTraceThresholdMs(100));
        slowStore.addSpan(
                rootSpan("slow-http", "GET /orders", Span.Kind.SERVER, 150).build());
        slowStore.addSpan(
                rootSpan("slow-consumer", "receive", Span.Kind.CONSUMER, 150).build());
        slowStore.addSpan(
                rootSpan("fast-http", "GET /users", Span.Kind.SERVER, 50).build());
        TraceInsightsService slowService = newService(slowStore);

        TraceInsightsResponse response = slowService.getInsights(10, TraceBucket.SLOW, "http_request", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("slow-http");
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 0, 2));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(2, 0, 1));
    }

    /** Beside the wildcard type, a blank operation is the other half of "nothing filtered", so no counting pass runs. */
    @Test
    void aBlankRootOperationFilterIsNoFilterAtAll() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", "   ");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
        assertThat(response.filteredBucketCounts()).isNull();
    }

    @Test
    void aRootActionTypeFilterIsTrimmedAndReadCaseInsensitively() {
        addTrace("trace1", 100, false);
        addConsumerTrace("trace2", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, " Http_Request ", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("trace1");
    }

    /** A root span without a name cannot match an operation filter, and must not throw trying. */
    @Test
    void aRootSpanWithoutANameNeverMatchesAnOperationFilter() {
        store.addSpan(span("span-unnamed")
                .in("unnamed")
                .named(null)
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build());
        addTraceWithOperation("named", "GET /users", 100);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, null, "users");

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactly("named");
    }

    /** The limit bounds the page only; the counts describe the store either way. */
    @Test
    void aZeroLimitListsNothingButStillCounts() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(0, TraceBucket.ALL, null, null);

        assertThat(response.traces()).isEmpty();
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    /**
     * A fragment is somebody else's trace seen through a hole, not a trace of its own, so
     * no listing carries it - the wildcard, which exists to show the store as it stands,
     * included. Left in, it would surface as a phantom entry that vanishes again once the
     * skipped root reaches the exporter and discards the id.
     */
    @Test
    void anIncompleteFragmentIsListedByNoRequest() {
        addTrace("http1", 100, false);
        addExcludedRequestFragment("fragment1");

        assertThat(service.getInsights(10, TraceBucket.ALL, "*", null).traces())
                .extracting(TraceTree::traceId)
                .containsExactly("http1");
        assertThat(service.getInsights(10, TraceBucket.ALL, null, null).traces())
                .extracting(TraceTree::traceId)
                .containsExactly("http1");
    }

    /**
     * The case the fragment rule must not catch: an inbound request continuing a caller's
     * trace carries a parent id that never arrives either, but a SERVER root is the start
     * of this application's work and a trace in its own right.
     */
    @Test
    void anInboundTraceContinuingARemoteCallerIsStillListed() {
        store.addSpan(span("span-inbound")
                .in("remote1")
                .parent("caller-span-in-another-service")
                .named("GET /users/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.method", "GET"))
                .build());

        assertThat(service.getInsights(10, TraceBucket.ALL, "*", null).traces())
                .extracting(TraceTree::traceId)
                .containsExactly("remote1");
    }

    /** The way past the default view's exclusion for a client that wants the store as it is. */
    @Test
    void aWildcardTypeFilterListsMaintenanceTracesAlongsideEveryOther() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", null);

        assertThat(response.traces()).extracting(TraceTree::traceId).containsExactlyInAnyOrder("http1", "pool1");
    }

    @Test
    void theListFiltersToMaintenanceTracesWhenARequestNamesThem() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "maintenance", null);

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

    /** Only a request that filters nothing at all - every type, any operation - skips the extra bucket passes. */
    @Test
    void filteredBucketCountsAreOmittedWhenEveryTypeIsAskedFor() {
        addTrace("trace1", 100, false);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", null);

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

        HttpExchange exchange = result.orElseThrow().httpExchange();
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
        TraceTree withDup = service.getTraceInsights("withDup").orElseThrow();
        TraceTree withoutDup = service.getTraceInsights("withoutDup").orElseThrow();

        // folding the duplicate away on write must produce the exact tree the
        // never-duplicated trace produces
        assertThat(withDup.summary().spans().count())
                .isEqualTo(withoutDup.summary().spans().count());
        assertThat(withDup.rootSpan().children()).hasSize(1);
        assertThat(withDup.queries()).hasSize(1);
        assertThat(withDup.queries().get(0).statement().text())
                .isEqualTo(withoutDup.queries().get(0).statement().text());
    }

    @Test
    void theDetailSurfacesTheTruncatedFlagFromTheBundle() {
        InMemoryTraceStore cappedStore = TraceStores.with(p -> p.setMaxSpansPerTrace(2));
        for (int i = 1; i <= 3; i++) {
            cappedStore.addSpan(rootSpanWithoutTags("t1", "s" + i, "op" + i));
        }
        TraceInsightsService cappedService = newService(cappedStore);

        Optional<TraceTree> result = cappedService.getTraceInsights("t1");

        assertThat(result).isPresent();
        assertThat(result.get().truncated()).isTrue();
    }

    @Test
    void theListSurfacesTheTruncatedFlagToo() {
        InMemoryTraceStore cappedStore = TraceStores.with(p -> p.setMaxSpansPerTrace(1));
        cappedStore.addSpan(rootSpanWithoutTags("t1", "s1", "op1"));
        cappedStore.addSpan(rootSpanWithoutTags("t1", "s2", "op2"));
        TraceInsightsService cappedService = newService(cappedStore);

        TraceInsightsResponse response = cappedService.getInsights(10, TraceBucket.ALL, null, null);

        assertThat(response.traces()).extracting(TraceTree::truncated).containsExactly(true);
    }

    @Test
    void anAsyncEntryIsListedBesideTheTraceThatTriggeredIt() {
        addTraceWithAsyncWork("http1", 50, 240_000);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", null);

        assertThat(response.traces())
                .extracting(TraceTree::rootActionType, TraceTree::durationMs)
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, 50L), tuple(RootActionType.ASYNC_TASK, 240_000L));
    }

    @Test
    void anAsyncRowNamesTheSpanItIsRootedAtAndItsEnclosingTrace() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "async_task", null);

        assertThat(response.traces()).singleElement().satisfies(row -> {
            assertThat(row.traceId()).isEqualTo("http1");
            assertThat(row.subtree()).isEqualTo(new SubtreeView("async-http1", true));
        });
    }

    @Test
    void anAsyncRowCountsOnlyTheLogsEmittedInsideItsOwnSubtree() {
        addTraceWithAsyncWork("http1", 50, 1_000);
        store.addLog(log("http1")
                .inSpan("span-http1")
                .at("ERROR")
                .saying("the request failed")
                .build());
        store.addLog(log("http1").inSpan("async-http1").saying("enriching").build());

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", null);

        assertThat(response.traces())
                .extracting(TraceTree::rootActionType, tree -> tree.summary().logs())
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, new TraceTabSummary.LogsSummary(2, 1, 0)),
                        tuple(RootActionType.ASYNC_TASK, new TraceTabSummary.LogsSummary(1, 0, 0)));
    }

    @Test
    void anAsyncRowEndsOkWhenOnlyTheRequestThatDispatchedItFailed() {
        addFailedRequestWithAsyncWork("http1", 50, 1_000);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.ALL, "*", null);

        assertThat(response.traces())
                .extracting(TraceTree::rootActionType, TraceTree::status)
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, TraceStatus.HAS_ERRORS),
                        tuple(RootActionType.ASYNC_TASK, TraceStatus.OK));
    }

    @Test
    void aTypeFilterSelectsRowsRatherThanTraces() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        assertThat(service.getInsights(10, TraceBucket.ALL, "http_request", null)
                        .traces())
                .extracting(TraceTree::rootActionType)
                .containsExactly(RootActionType.HTTP_REQUEST);
        assertThat(service.getInsights(10, TraceBucket.ALL, "async_task", null).traces())
                .extracting(TraceTree::rootActionType)
                .containsExactly(RootActionType.ASYNC_TASK);
    }

    /**
     * Background work that really ran is not a phantom awaiting its root, so it is listed -
     * unlike an excluded request's leftover fragment, which disappears on its own.
     */
    @Test
    void orphanedAsyncWorkIsListedWhereAnExcludedFragmentIsNot() {
        addOrphanedAsyncWork("async1", 3_700);
        addExcludedRequestFragment("fragment1");

        assertThat(service.getInsights(10, TraceBucket.ALL, "*", null).traces())
                .extracting(TraceTree::traceId)
                .containsExactly("async1");
    }

    @Test
    void theLimitCountsRowsNotTraces() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        assertThat(service.getInsights(1, TraceBucket.ALL, "*", null).traces()).hasSize(1);
        assertThat(service.getInsights(2, TraceBucket.ALL, "*", null).traces()).hasSize(2);
    }

    /**
     * A trace with two matching async rows must not outweigh the one trace it is: the
     * filtered count describes traces, so it stays a subset of the unfiltered total beside
     * it rather than a row count that can exceed it. Requesting SLOW - rather than the
     * bucket the two async rows actually land in - also forces the recursive passes over
     * ALL and ERRORS, not just the short-circuit for the requested bucket.
     */
    @Test
    void filteredBucketCountsCountDistinctTracesNotRows() {
        addTraceWithTwoAsyncTasks("http1", 1_500);

        TraceInsightsResponse response = service.getInsights(10, TraceBucket.SLOW, "async_task", null);

        assertThat(response.traces()).hasSize(2);
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 1));
    }

    /**
     * The list is newest first by arrival, and an async row arrives when its task ends: often
     * long after the request that dispatched it, with newer traces listed in between.
     */
    @Test
    void anAsyncRowIsListedByWhenItArrivedRatherThanBesideItsTrigger() {
        store.addSpan(rootSpan("http1", "test-operation", Span.Kind.SERVER, 50).build());
        addTrace("http2", 20, false);
        addTrace("http3", 20, false);
        store.addSpan(asyncTaskSpan("http1", 50, 240_000));

        assertThat(service.getInsights(10, TraceBucket.ALL, "*", null).traces())
                .extracting(TraceTree::traceId, TraceTree::rootActionType)
                .containsExactly(
                        tuple("http1", RootActionType.ASYNC_TASK),
                        tuple("http3", RootActionType.HTTP_REQUEST),
                        tuple("http2", RootActionType.HTTP_REQUEST),
                        tuple("http1", RootActionType.HTTP_REQUEST));
    }

    @Test
    void oneTracesAsyncRowsAreListedNewestFirstToo() {
        addTraceWithTwoAsyncTasks("http1", 50);

        assertThat(service.getInsights(10, TraceBucket.ALL, "*", null).traces())
                .extracting(row -> row.rootSpan().spanId())
                .containsExactly("async-b-http1", "async-a-http1", "span-http1");
    }

    /**
     * A bucket lists a trace from when it entered that bucket, so a task that ended before
     * its trace failed sits under the trace rather than among older failures.
     */
    @Test
    void anAsyncRowThatArrivedBeforeItsTraceEnteredTheBucketStaysUnderIt() {
        addTraceWithAsyncWork("http1", 50, 1_000);
        addTrace("http2", 20, true);
        store.addLog(log("http1")
                .inSpan("span-http1")
                .at("ERROR")
                .saying("failed after the task ended")
                .build());

        assertThat(service.getInsights(10, TraceBucket.ERRORS, "*", null).traces())
                .extracting(TraceTree::traceId, TraceTree::rootActionType)
                .containsExactly(
                        tuple("http1", RootActionType.HTTP_REQUEST),
                        tuple("http1", RootActionType.ASYNC_TASK),
                        tuple("http2", RootActionType.HTTP_REQUEST));
    }

    private static SpanData rootSpanWithoutTags(String traceId, String spanId, String name) {
        return span(spanId).in(traceId).named(name).build();
    }

    private TraceInsightsService newService(TraceStore store) {
        return newService(store, List.of(), List.of());
    }

    private TraceInsightsService newService(
            TraceStore store, List<String> exclusions, List<String> applicationPackages) {
        return new TraceInsightsService(
                store,
                traceTreeMapper,
                issueDetector,
                queryExtractor,
                new StackTraceRules(exclusions, applicationPackages));
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

    /**
     * The fragment an excluded request leaves behind. Peekaboot skips the root span of a
     * request on an excluded prefix - its own, the actuator's - so a connection acquired
     * while serving it is the only span stored under that trace id, and reads as the
     * bundle's root despite carrying a parent id that never arrives.
     */
    private void addExcludedRequestFragment(String traceId) {
        store.addSpan(jdbcConnection("span-fragment-" + traceId)
                .in(traceId)
                .parent("root-span-never-exported")
                .build());
    }

    /** An HTTP request that dispatched background work which outlived it. */
    private void addTraceWithAsyncWork(String traceId, long requestMs, long asyncMs) {
        store.addSpan(
                rootSpan(traceId, "test-operation", Span.Kind.SERVER, requestMs).build());
        store.addSpan(asyncTaskSpan(traceId, requestMs, asyncMs));
    }

    /** The same hand-off, from a request that then failed while the background work did not. */
    private void addFailedRequestWithAsyncWork(String traceId, long requestMs, long asyncMs) {
        store.addSpan(rootSpan(traceId, "test-operation", Span.Kind.SERVER, requestMs)
                .error("Test error", "TestException")
                .build());
        store.addSpan(asyncTaskSpan(traceId, requestMs, asyncMs));
    }

    /** The span Peekaboot's decorator exports for one task, dispatched 10ms before the request ended. */
    private static SpanData asyncTaskSpan(String traceId, long requestMs, long asyncMs) {
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
    private void addTraceWithTwoAsyncTasks(String traceId, long requestMs) {
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

    /** Background work whose triggering trace was evicted before it arrived. */
    private void addOrphanedAsyncWork(String traceId, long asyncMs) {
        store.addSpan(asyncTask("async-" + traceId)
                .in(traceId)
                .parent("gone")
                .at(0, asyncMs)
                .build());
    }

    /** The exact root datasource-micrometer exports for a standalone pool acquisition. */
    private void addConnectionPoolTrace(String traceId) {
        store.addSpan(jdbcConnection("span-" + traceId).in(traceId).build());
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
