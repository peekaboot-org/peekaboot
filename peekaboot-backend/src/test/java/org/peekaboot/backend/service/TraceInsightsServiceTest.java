package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.HttpRequest;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.ApiProblem;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.testsupport.TraceListings;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;
import tools.jackson.databind.JsonNode;

class TraceInsightsServiceTest {

    private InMemoryTraceStore store;
    private TraceInsightsService service;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        service = newService(store);
    }

    /** The SLOW reason is the Slow bucket's rule, so the list's badge and the bucket agree. */
    @Test
    void aTraceIsSlowAtTheSlowTraceThreshold() {
        long thresholdMs = new PeekabootTracingProperties().getSlowTraceThresholdMs();
        addTrace("fast", thresholdMs - 1, false);
        addTrace("slow", thresholdMs, false);

        TraceListResponse response = service.listTraces(listing().limit(10).build());

        assertThat(response.traces())
                .extracting(
                        row -> row.summary().traceId(),
                        trace -> trace.summary().reasons().stream()
                                .anyMatch(reason -> reason.kind() == TraceReason.Kind.SLOW))
                .containsExactlyInAnyOrder(tuple("fast", false), tuple("slow", true));
    }

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
        SqlFormatter recording = sql -> {
            formatted.add(sql);
            return sql;
        };
        TraceInsightsService recordingService = new TraceInsightsService(
                store,
                new MaskingEngine(),
                recording,
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
        addTraceWithDbSpan("t1", 100);

        TraceResource detail = recordingService.getTrace("t1").orElseThrow();

        assertThat(formatted).containsExactly("SELECT * FROM users WHERE id = ?");
        assertThat(detail.data().rootSpan().children().getFirst().statement().formatted())
                .isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(detail.data().queries().getFirst().statement())
                .isSameAs(detail.data().rootSpan().children().getFirst().statement());
    }

    @Test
    void anEmptyStoreYieldsAnEmptyTraceList() {
        TraceListResponse response = service.listTraces(listing().limit(10).build());

        assertThat(response.traces()).isEmpty();
    }

    @Test
    void tracingIsAvailableExactlyWhenThereIsATraceStore() {
        assertThat(service.isTracingAvailable()).isTrue();
        assertThat(newService(null).isTracingAvailable()).isFalse();
    }

    @Test
    void theListingReadsTheRequestedBucket() {
        store.addSpan(span("s1")
                .in("terr")
                .exception("java.lang.RuntimeException", "boom")
                .build());
        store.addSpan(span("s2").in("tok").build());

        TraceListResponse errors =
                service.listTraces(listing().limit(10).bucket("errors").build());
        TraceListResponse all = service.listTraces(listing().limit(10).build());

        assertThat(errors.traces()).extracting(row -> row.summary().traceId()).containsExactly("terr");
        assertThat(all.traces()).hasSize(2);
    }

    @Test
    void responseCarriesBucketCounts() {
        store.addSpan(span("s1")
                .in("terr")
                .exception("java.lang.RuntimeException", "boom")
                .build());

        TraceListResponse response = service.listTraces(listing().limit(10).build());

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(1, 1, 0));
    }

    @Test
    void nullStoreYieldsEmptyResponseWithZeroCounts() {
        TraceInsightsService serviceWithNullStore = newService(null);

        TraceListResponse response =
                serviceWithNullStore.listTraces(listing().limit(10).build());

        assertThat(response.traces()).isEmpty();
        assertThat(response.bucketCounts()).isEqualTo(BucketCounts.empty());
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

    /** The list feeds the Traces tab's log badges; the counts come from the logs the bundle already holds. */
    @Test
    void eachTracesLogsAreCountedByLevel() {
        addTrace("trace1", 100, false);
        addTrace("trace2", 100, false);
        for (String level : List.of("ERROR", "WARN", "INFO")) {
            store.addLog(log("trace1").inSpan("span-trace1").at(level).build());
        }

        TraceListResponse response = service.listTraces(listing().limit(10).build());

        assertThat(response.traces())
                .extracting(
                        row -> row.summary().traceId(),
                        row -> row.summary().counts().logs())
                .containsExactlyInAnyOrder(
                        tuple("trace1", new TraceSummary.LogCounts(3, 1, 1)),
                        tuple("trace2", new TraceSummary.LogCounts(0, 0, 0)));
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

    /** A full row with nothing to list still says "none" as a list, never as null. */
    @Test
    void aFullRowCarriesEmptyLogAndQueryListsRatherThanNulls() {
        addTrace("trace1", 100, false);

        TraceListResponse response =
                service.listTraces(listing().limit(10).view("full").build());

        assertThat(response.traces().getFirst().data().logs()).isEmpty();
        assertThat(response.traces().getFirst().data().queries()).isEmpty();
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
    void theListFiltersByRootActionType() {
        addTrace("trace1", 100, false); // SERVER kind, no tags -> HTTP_REQUEST (default)
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType("http_request").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
    }

    @Test
    void theListFiltersByRootOperationWithAPartialCaseInsensitiveMatch() {
        addTraceWithOperation("trace1", "GET /api/Users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootOperation("users").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
    }

    @Test
    void theListFiltersByMultipleCommaSeparatedRootActionTypes() {
        addTrace("trace1", 100, false); // SERVER kind -> HTTP_REQUEST
        addConsumerTrace("trace2", 100); // CONSUMER kind -> MESSAGE_CONSUMER
        addScheduledJobTrace("trace3", 100); // scheduled-task tags -> SCHEDULED_JOB

        TraceListResponse response = service.listTraces(listing()
                .limit(10)
                .rootActionType("http_request,message_consumer")
                .build());

        assertThat(response.traces())
                .extracting(row -> row.summary().traceId())
                .containsExactlyInAnyOrder("trace1", "trace2");
    }

    @Test
    void refusesAnUnknownTypeInTheFilter() {
        assertThatThrownBy(() -> service.listTraces(
                        listing().limit(10).rootActionType("http_request,bogus").build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("rootActionType");
                    assertThat(e.problem().detail())
                            .contains("'bogus'")
                            .contains("HTTP_REQUEST")
                            .endsWith(", * on its own.");
                });
    }

    /** The refusal must not list {@code *} as accepted where it refuses that very token. */
    @Test
    void refusesTheWildcardInAListSayingItStandsAlone() {
        assertThatThrownBy(() -> service.listTraces(
                        listing().limit(10).rootActionType("http_request,*").build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().detail())
                                .startsWith("Unknown rootActionType '*'.")
                                .endsWith(", UNKNOWN, * on its own."));
    }

    /** The refusal is about the request, so it does not wait on there being a store to filter. */
    @Test
    void refusesAnUnknownTypeWithTracingOff() {
        assertThatThrownBy(() -> newService(null)
                        .listTraces(listing().limit(10).rootActionType("bogus").build()))
                .isInstanceOf(ApiProblemException.class);
    }

    /** The summary alone keeps the dashboard's poll small, so a request has to ask for the data. */
    @Test
    void aMissingOrBlankViewMeansSummary() {
        addTrace("trace1", 100, false);

        assertThat(service.listTraces(listing().limit(10).build()).traces())
                .singleElement()
                .satisfies(row -> assertThat(row.data()).isNull());
        assertThat(service.listTraces(listing().limit(10).view(" ").build()).traces())
                .singleElement()
                .satisfies(row -> assertThat(row.data()).isNull());
        assertThat(service.listTraces(listing().limit(10).view("Full").build()).traces())
                .singleElement()
                .satisfies(row -> assertThat(row.data()).isNotNull());
    }

    @Test
    void anUnknownViewIsRefusedNamingTheAcceptedValues() {
        assertThatThrownBy(() ->
                        service.listTraces(listing().limit(10).view("sumary").build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.problem())
                                .extracting(ApiProblem::parameter, ApiProblem::detail)
                                .containsExactly("view", "Unknown view 'sumary'. Accepted: SUMMARY, FULL."));
    }

    @Test
    void refusesAnUnknownViewWithTracingOff() {
        assertThatThrownBy(() -> newService(null)
                        .listTraces(listing().limit(10).view("sumary").build()))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void refusesAnEmptyTypeInTheFilter() {
        assertThatThrownBy(() -> service.listTraces(
                        listing().limit(10).rootActionType("http_request,").build()))
                .isInstanceOf(ApiProblemException.class);
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

        TraceListResponse response = service.listTraces(listing().limit(10).build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("http1");
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

        TraceListResponse response = slowService.listTraces(listing()
                .limit(10)
                .bucket("slow")
                .rootActionType("http_request")
                .build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("slow-http");
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 0, 2));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(2, 0, 1));
    }

    /** Beside the wildcard type, a blank operation is the other half of "nothing filtered", so no counting pass runs. */
    @Test
    void aBlankRootOperationFilterIsNoFilterAtAll() {
        addTrace("trace1", 100, false);

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType("*").rootOperation("   ").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
        assertThat(response.filteredBucketCounts()).isNull();
    }

    @Test
    void aRootActionTypeFilterIsTrimmedAndReadCaseInsensitively() {
        addTrace("trace1", 100, false);
        addConsumerTrace("trace2", 100);

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType(" Http_Request ").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
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

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootOperation("users").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("named");
    }

    /** The limit bounds the page only; the counts describe the store either way. */
    @Test
    void theCountsDescribeTheStoreWhateverTheLimit() {
        addTrace("trace1", 100, false);
        addTrace("trace2", 100, false);
        addTrace("trace3", 100, true);

        TraceListResponse response = service.listTraces(listing().limit(1).build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace3");
        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 1, 0));
    }

    /** A clamp answered a typo with another list than the one asked for. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "10001", "2147483648", "abc", "1.5"})
    void refusesALimitOutsideOneToTenThousand(String limit) {
        assertThatThrownBy(() -> service.listTraces(listing().limit(limit).build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("limit");
                    assertThat(e.problem().detail())
                            .isEqualTo("limit must be an integer from 1 to 10000, got '" + limit + "'.");
                });
    }

    @Test
    void takesALimitAtEitherEndOfItsRange() {
        addTrace("trace1", 100, false);
        addTrace("trace2", 100, false);

        assertThat(service.listTraces(listing().limit(1).build()).traces()).hasSize(1);
        assertThat(service.listTraces(listing().limit(10_000).build()).traces()).hasSize(2);
    }

    @Test
    void listsAHundredWithoutALimit() {
        for (int i = 0; i < 101; i++) {
            addTrace("trace" + i, 10, false);
        }

        assertThat(service.listTraces(listing().build()).traces()).hasSize(100);
        assertThat(service.listTraces(listing().limit(" ").build()).traces()).hasSize(100);
    }

    @Test
    void readsTheBucketInAnyCaseAndListsAllWithoutOne() {
        addTrace("ok", 10, false);
        addTrace("failed", 10, true);

        assertThat(listedIds(listing().bucket("ERRORS"))).containsExactly("failed");
        assertThat(listedIds(listing().bucket("Errors"))).containsExactly("failed");
        assertThat(listedIds(listing())).containsExactly("failed", "ok");
        assertThat(listedIds(listing().bucket("  "))).containsExactly("failed", "ok");
    }

    /** Read before the store is consulted, so a typo is refused even while tracing is off. */
    @Test
    void refusesAnUnknownBucketWhileTracingIsOff() {
        assertThatThrownBy(() ->
                        newService(null).listTraces(listing().bucket("eror").build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("bucket");
                    assertThat(e.problem().detail()).isEqualTo("Unknown bucket 'eror'. Accepted: ALL, ERRORS, SLOW.");
                });
    }

    @Test
    void refusesAnOutOfRangeLimitWhileTracingIsOff() {
        assertThatThrownBy(() -> newService(null).listTraces(listing().limit(0).build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("limit"));
    }

    @Test
    void refusesAnUnknownCursorWhileTracingIsOff() {
        assertThatThrownBy(
                        () -> newService(null).listTraces(listing().cursor("x").build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("cursor"));
    }

    @Test
    void pagesNewestFirstUntilNoNextIsLeft() {
        for (int i = 1; i <= 5; i++) {
            addTrace("t" + i, 10, false);
        }

        TraceListResponse first = service.listTraces(listing().limit(2).build());
        TraceListResponse second =
                service.listTraces(listing().limit(2).cursor(first.next()).build());
        TraceListResponse third =
                service.listTraces(listing().limit(2).cursor(second.next()).build());

        assertThat(idsOf(first)).containsExactly("t5", "t4");
        assertThat(idsOf(second)).containsExactly("t3", "t2");
        assertThat(idsOf(third)).containsExactly("t1");
        assertThat(third.next()).isNull();
    }

    /** A page that ends at the last row says so, instead of handing out a cursor to an empty page. */
    @Test
    void aFullLastPageHasNoNext() {
        addTrace("t1", 10, false);
        addTrace("t2", 10, false);

        assertThat(service.listTraces(listing().limit(2).build()).next()).isNull();
    }

    @Test
    void aTraceArrivingBetweenPagesListsAboveTheCursor() {
        for (int i = 1; i <= 5; i++) {
            addTrace("t" + i, 10, false);
        }
        TraceListResponse first = service.listTraces(listing().limit(2).build());

        addTrace("t6", 10, false);
        TraceListResponse second =
                service.listTraces(listing().limit(2).cursor(first.next()).build());
        TraceListResponse third =
                service.listTraces(listing().limit(2).cursor(second.next()).build());

        assertThat(idsOf(second)).containsExactly("t3", "t2");
        assertThat(idsOf(third)).containsExactly("t1");
        assertThat(idsOf(service.listTraces(listing().limit(2).build()))).containsExactly("t6", "t5");
    }

    /** The cursor names a position, so the row it was taken from may leave the store meanwhile. */
    @Test
    void aCursorOutlivesTheRowItNames() {
        for (int i = 1; i <= 5; i++) {
            addTrace("t" + i, 10, false);
        }
        TraceListResponse first = service.listTraces(listing().limit(2).build());

        store.discard("t4");

        assertThat(idsOf(service.listTraces(
                        listing().limit(2).cursor(first.next()).build())))
                .containsExactly("t3", "t2");
    }

    /** Log-only traces enter All before any span arrives, so all three share one listedAt. */
    @Test
    void tracesListedAtOneMomentPageOneByOne() {
        store.addLog(log("a").build());
        store.addLog(log("b").build());
        store.addLog(log("c").build());

        assertThat(pagedOneByOne(listing()))
                .extracting(row -> row.summary().traceId())
                .containsExactly("c", "b", "a");
    }

    /**
     * An ERROR log after both tasks ended puts the trace into Errors with a stamp later than either
     * entry span, so all three rows share it and only the entry order sets them apart.
     */
    @Test
    void aTraceAndItsAsyncRowsPageInTheirOwnOrder() {
        addTraceWithTwoAsyncTasks("t1", 50);
        store.addLog(log("t1")
                .inSpan("span-t1")
                .at("ERROR")
                .saying("failed after the tasks ended")
                .build());

        assertThat(pagedOneByOne(listing().bucket("errors").rootActionType("*")))
                .extracting(row -> row.summary().subtree() == null
                        ? "trace"
                        : row.summary().subtree().rootSpanId())
                .containsExactly("trace", "async-a-t1", "async-b-t1");
    }

    /**
     * A request's row appears when its root ends, at the position its trace took on entering the bucket
     * with its first span. So it can list below a page already served.
     */
    @Test
    void aRequestWhoseRootEndsLateListsWhereItsTraceEntered() {
        store.addSpan(span("span-a-child")
                .in("a")
                .parent("span-a")
                .kind(Span.Kind.SERVER)
                .at(5, 20)
                .build());
        addTrace("b", 10, false);
        addTrace("c", 10, false);
        TraceListResponse first = service.listTraces(listing().limit(1).build());
        TraceListResponse second =
                service.listTraces(listing().limit(1).cursor(first.next()).build());

        store.addSpan(rootSpan("a", "test-operation", Span.Kind.SERVER, 100).build());

        assertThat(idsOf(first)).containsExactly("c");
        assertThat(idsOf(second)).containsExactly("b");
        assertThat(second.next()).isNull();
        assertThat(listedIds(listing())).containsExactly("c", "b", "a");
        assertThat(idsOf(service.listTraces(listing().cursor(first.next()).build())))
                .containsExactly("b", "a");
    }

    /** An older row the filter rejects is no reason for another page. */
    @Test
    void aPageHasNoNextWhenOnlyUnmatchedRowsAreOlder() {
        addRequest("missed", 404);
        addRequest("m1", 500);
        addRequest("m2", 500);

        TraceListResponse page =
                service.listTraces(listing().statusCode("500").limit(2).build());

        assertThat(idsOf(page)).containsExactly("m2", "m1");
        assertThat(page.next()).isNull();
    }

    /** All evicted the failed trace and Errors kept it; its next event lists it at the top, above the cursor. */
    @Test
    void aTraceEnteringTheBucketAgainListsAboveTheCursor() {
        InMemoryTraceStore capped = TraceStores.with(p -> p.setMaxTraces(3));
        TraceInsightsService cappedService = newService(capped);
        capped.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 10)
                .exception("TestException", "Test error")
                .build());
        for (int i = 2; i <= 4; i++) {
            capped.addSpan(
                    rootSpan("t" + i, "test-operation", Span.Kind.SERVER, 10).build());
        }
        TraceListResponse first = cappedService.listTraces(listing().limit(1).build());

        capped.addLog(log("t1").saying("late").build());

        assertThat(idsOf(first)).containsExactly("t4");
        assertThat(idsOf(cappedService.listTraces(listing().cursor(first.next()).build())))
                .containsExactly("t3");
        assertThat(idsOf(cappedService.listTraces(listing().build()))).containsExactly("t1", "t4", "t3");
    }

    /** A prefix or a substring of the pattern is another route. */
    @Test
    void filtersByTheMatchedRouteExactly() {
        addRequest("a", "/persons/{id}", "/persons/1", 200, 0, 10);
        addRequest("b", "/persons/{id}/orders", "/persons/1/orders", 200, 0, 10);
        addRequest("c", "/persons/{id}", "/persons/2", 200, 0, 10);
        addRequest("d", "/persons", "/persons", 200, 0, 10);

        assertThat(listedIds(listing().route("/persons/{id}"))).containsExactly("c", "a");
    }

    @Test
    void filtersByThePathsStart() {
        addRequest("a", "/api/orders/{id}", "/api/orders/1", 200, 0, 10);
        addRequest("b", "/x/api/orders", "/x/api/orders", 200, 0, 10);
        addRequest("c", "/api/orders/{id}", "/api/orders/22", 200, 0, 10);
        addRequest("d", "/api/order-lines/{id}", "/api/order-lines/3", 200, 0, 10);

        assertThat(listedIds(listing().path("/api/orders"))).containsExactly("c", "a");
    }

    /** Only 500 matches; 503 does not. */
    @Test
    void filtersByTheExactStatusCode() {
        addRequest("a", 500);
        addRequest("b", 200);
        addRequest("c", 503);
        addRequest("d", 500);
        addRequest("e", 404);

        assertThat(listedIds(listing().statusCode("500"))).containsExactly("d", "a");
    }

    /** {@code ERROR} is the store's failure flag, so a request that only logged an error is one, and a 4xx is none. */
    @Test
    void filtersByOutcome() {
        addRequest("ok", 200);
        addTrace("failed", 10, true);
        store.addLog(log("inflight").build());
        addRequest("logged", 200);
        store.addLog(log("logged")
                .inSpan("span-logged")
                .at("ERROR")
                .saying("gateway down")
                .build());
        addRequest("clientError", 404);

        assertThat(listedIds(listing().outcome("error"))).containsExactly("logged", "failed");
        assertThat(listedIds(listing().outcome("OK"))).containsExactly("clientError", "ok");
        assertThat(listedIds(listing().outcome("in_progress"))).containsExactly("inflight");
    }

    @Test
    void filtersByTheShortestDurationInclusive() {
        addTrace("a", 99, false);
        addTrace("b", 100, false);
        addTrace("c", 101, false);
        addTrace("d", 2_000, false);

        assertThat(listedIds(listing().minDurationMs("100"))).containsExactly("d", "c", "b");
    }

    /** {@code since} is in, {@code until} is out, and a trace that has no start yet is in no window. */
    @Test
    void filtersByTheStartTimeHalfOpen() {
        addRequest("a", "/r", "/r", 200, 0, 10);
        addRequest("b", "/r", "/r", 200, 1_000, 10);
        addRequest("c", "/r", "/r", 200, 2_000, 10);
        addRequest("d", "/r", "/r", 200, 3_000, 10);
        store.addLog(log("e").build());

        assertThat(listedIds(listing().since("1970-01-01T00:00:01Z").until("1970-01-01T00:00:03Z")))
                .containsExactly("c", "b");
        assertThat(listedIds(listing().since("1970-01-01T00:00:00Z"))).containsExactly("d", "c", "b", "a");
        assertThat(listedIds(listing())).contains("e");
    }

    /** The task failed and ran long under a quick request that did not. Its row is judged on the task. */
    @Test
    void anAsyncRowIsFilteredOnItsOwnTask() {
        store.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 50)
                .tag("http.route", "/jobs")
                .tag("http.response.status_code", "200")
                .build());
        store.addSpan(asyncTask("async-t1")
                .in("t1")
                .parent("span-t1")
                .at(40, 3_000)
                .exception("java.lang.IllegalStateException", "task failed")
                .build());

        assertThat(rowsOf(listing().rootActionType("*").outcome("error")))
                .containsExactly(tuple("t1", RootActionType.ASYNC_TASK), tuple("t1", RootActionType.HTTP_REQUEST));
        assertThat(rowsOf(listing().rootActionType("*").minDurationMs("1000")))
                .containsExactly(tuple("t1", RootActionType.ASYNC_TASK));
        assertThat(rowsOf(listing().rootActionType("*").route("/jobs")))
                .containsExactly(tuple("t1", RootActionType.HTTP_REQUEST));
    }

    /** The task succeeded under a request that failed, so only its row reads {@code OK}. */
    @Test
    void anAsyncRowThatSucceededListsUnderOk() {
        addFailedRequestWithAsyncWork("t1", 50, 3_000);

        assertThat(rowsOf(listing().rootActionType("*").outcome("ok")))
                .containsExactly(tuple("t1", RootActionType.ASYNC_TASK));
    }

    /** The trace starts with its request, and the task it dispatched starts two seconds later. */
    @Test
    void anAsyncRowIsWindowedOnItsOwnStart() {
        store.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 50).build());
        store.addSpan(
                asyncTask("async-t1").in("t1").parent("span-t1").at(2_000, 100).build());

        assertThat(rowsOf(listing().rootActionType("*").since("1970-01-01T00:00:01Z")))
                .containsExactly(tuple("t1", RootActionType.ASYNC_TASK));
        assertThat(rowsOf(listing().rootActionType("*").until("1970-01-01T00:00:01Z")))
                .containsExactly(tuple("t1", RootActionType.HTTP_REQUEST));
    }

    /** Every type is listed, so the one filter each row names is what makes the counts filtered ones. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "route | /fixtures/{id}",
                "path | /fixtures",
                "statusCode | 200",
                "outcome | OK",
                "minDurationMs | 0",
                "since | 1970-01-01T00:00:00Z",
                "until | 2100-01-01T00:00:00Z"
            })
    void eachFilterMakesTheCountsFilteredOnes(String parameter, String value) {
        addRequest("a", 200);

        TraceListResponse response = service.listTraces(
                listingFilteredBy(parameter, value).rootActionType("*").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("a");
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    /** A verdict filter maps an async row to judge it, and the page serves that same mapping. */
    @Test
    void aVerdictFilterMapsAnAsyncRowOnce() {
        List<String> formatted = new ArrayList<>();
        SqlFormatter recording = sql -> {
            formatted.add(sql);
            return sql;
        };
        TraceInsightsService recordingService = new TraceInsightsService(
                store,
                new MaskingEngine(),
                recording,
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
        store.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 50).build());
        store.addSpan(span("db-t1")
                .in("t1")
                .parent("async-t1")
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(60, 20)
                .tags(Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users WHERE id = ?"))
                .build());
        store.addSpan(
                asyncTask("async-t1").in("t1").parent("span-t1").at(40, 100).build());

        List<TraceResource> rows = recordingService
                .listTraces(listing()
                        .rootActionType("async_task")
                        .minDurationMs("0")
                        .build())
                .traces();

        assertThat(rows).extracting(row -> row.summary().rootActionType()).containsExactly(RootActionType.ASYNC_TASK);
        assertThat(formatted).containsExactly("SELECT * FROM users WHERE id = ?");
    }

    /** Every type is listed, so only {@code statusCode} makes the counts filtered ones. */
    @Test
    void theFilteredCountsCountWhatEveryFilterKeeps() {
        addRequest("a", 500);
        addRequest("b", 200);
        addRequest("c", 503);

        TraceListResponse response = service.listTraces(
                listing().rootActionType("*").statusCode("500").build());

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 2, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 1, 0));
    }

    /** What a form or a hand-built URL sends for a field left empty. */
    @Test
    void aBlankFilterFiltersNothing() {
        addRequest("a", 200);
        addRequest("b", 404);

        TraceListResponse response = service.listTraces(listing()
                .rootActionType("*")
                .route("")
                .path(" ")
                .statusCode("")
                .outcome(" ")
                .minDurationMs("")
                .since("")
                .until(" ")
                .build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("b", "a");
        assertThat(response.filteredBucketCounts()).isNull();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "statusCode | abc",
                "statusCode | 99",
                "statusCode | 600",
                "minDurationMs | -1",
                "outcome | failed",
                "since | yesterday",
                "until | 2026-13-01T00:00:00Z"
            })
    void refusesABadFilterNamingItWhileTracingIsOff(String parameter, String value) {
        TraceListings.Builder listing = listingFilteredBy(parameter, value);

        assertThatThrownBy(() -> newService(null).listTraces(listing.build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo(parameter));
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

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .extracting(row -> row.summary().traceId())
                .containsExactly("http1");
        assertThat(service.listTraces(listing().limit(10).build()).traces())
                .extracting(row -> row.summary().traceId())
                .containsExactly("http1");
    }

    /**
     * The case the fragment rule must not catch: an inbound request continuing a caller's
     * trace carries a parent id that never arrives either, but that parent is remote, so the
     * span is the start of this application's work and a trace in its own right.
     */
    @Test
    void anInboundTraceContinuingARemoteCallerIsStillListed() {
        store.addSpan(span("span-inbound")
                .in("remote1")
                .remoteParent("caller-span-in-another-service")
                .named("GET /users/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.method", "GET"))
                .build());

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .extracting(row -> row.summary().traceId())
                .containsExactly("remote1");
    }

    /** A SERVER span is no root on its kind alone: one whose local parent has not arrived is a request still running. */
    @Test
    void aRequestStillRunningIsListedOnceItsRootArrives() {
        store.addSpan(span("span-inner")
                .in("running1")
                .parent("span-running1")
                .named("GET /api/person/{id}")
                .kind(Span.Kind.SERVER)
                .at(5, 20)
                .build());

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .isEmpty();

        store.addSpan(rootSpan("running1", "GET /orders", Span.Kind.SERVER, 100).build());

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .extracting(row -> row.summary().traceId())
                .containsExactly("running1");
    }

    /** The way past the default view's exclusion for a client that wants the store as it is. */
    @Test
    void aWildcardTypeFilterListsMaintenanceTracesAlongsideEveryOther() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.traces())
                .extracting(row -> row.summary().traceId())
                .containsExactlyInAnyOrder("http1", "pool1");
    }

    @Test
    void theListFiltersToMaintenanceTracesWhenARequestNamesThem() {
        addTrace("http1", 100, false);
        addConnectionPoolTrace("pool1");

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType("maintenance").build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("pool1");
    }

    @Test
    void filteredBucketCountsAreReturnedWhileATypeFilterIsActive() {
        addTrace("trace1", 100, false); // HTTP_REQUEST, ok
        addTrace("trace2", 100, true); // HTTP_REQUEST, error
        addConsumerTrace("trace3", 100); // MESSAGE_CONSUMER, ok

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType("http_request").build());

        assertThat(response.bucketCounts()).isEqualTo(new BucketCounts(3, 1, 0));
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(2, 1, 0));
    }

    @Test
    void filteredBucketCountsAreReturnedForARootOperationFilter() {
        addTraceWithOperation("trace1", "GET /api/users", 100);
        addTraceWithOperation("trace2", "POST /orders", 100);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootOperation("users").build());

        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(1, 0, 0));
    }

    /** Root-level filtering decides the page and the counts; the limit cuts the page, never the counts. */
    @Test
    void theFilteredListRespectsTheLimitWhileCountsSeeEveryMatch() {
        for (int i = 1; i <= 5; i++) {
            addTrace("t" + i, 100, false); // HTTP_REQUEST
        }
        addConsumerTrace("c1", 100); // MESSAGE_CONSUMER

        TraceListResponse response = service.listTraces(
                listing().limit(2).rootActionType("http_request").build());

        assertThat(response.traces()).hasSize(2);
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(5, 0, 0));
    }

    /** Only a request that filters nothing at all - every type, any operation - skips the extra bucket passes. */
    @Test
    void filteredBucketCountsAreOmittedWhenEveryTypeIsAskedFor() {
        addTrace("trace1", 100, false);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.filteredBucketCounts()).isNull();
    }

    @Test
    void theRootOperationFilterMatchesAFullyQualifiedTaskTarget() {
        addTraceWithOperation("trace1", "task scheduler.fixedDelay", 100);
        addTraceWithOperation("trace2", "task scheduler.fixedRate", 100);

        TraceListResponse response = service.listTraces(listing()
                .limit(10)
                .rootOperation("org.peekaboot.example.Scheduler.fixedDelay")
                .build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
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

    @Test
    void theListCountsTheSpansTheCapDroppedToo() {
        InMemoryTraceStore cappedStore = TraceStores.with(p -> p.setMaxSpansPerTrace(1));
        cappedStore.addSpan(rootSpanWithoutTags("t1", "s1", "op1"));
        cappedStore.addSpan(rootSpanWithoutTags("t1", "s2", "op2"));
        TraceInsightsService cappedService = newService(cappedStore);

        TraceListResponse response =
                cappedService.listTraces(listing().limit(10).build());

        assertThat(response.traces())
                .extracting(row -> row.summary().counts().droppedSpans())
                .containsExactly(1);
    }

    @Test
    void anAsyncEntryIsListedBesideTheTraceThatTriggeredIt() {
        addTraceWithAsyncWork("http1", 50, 240_000);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.traces())
                .extracting(
                        row -> row.summary().rootActionType(),
                        row -> row.summary().durationMs())
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, 50L), tuple(RootActionType.ASYNC_TASK, 240_000L));
    }

    @Test
    void anAsyncRowNamesTheSpanItIsRootedAtAndItsEnclosingTrace() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        TraceListResponse response = service.listTraces(
                listing().limit(10).rootActionType("async_task").build());

        assertThat(response.traces()).singleElement().satisfies(row -> {
            assertThat(row.summary().traceId()).isEqualTo("http1");
            assertThat(row.summary().subtree()).isEqualTo(new SubtreeView("async-http1", true));
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

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.traces())
                .extracting(
                        row -> row.summary().rootActionType(),
                        row -> row.summary().counts().logs())
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, new TraceSummary.LogCounts(2, 1, 0)),
                        tuple(RootActionType.ASYNC_TASK, new TraceSummary.LogCounts(1, 0, 0)));
    }

    @Test
    void anAsyncRowEndsOkWhenOnlyTheRequestThatDispatchedItFailed() {
        addFailedRequestWithAsyncWork("http1", 50, 1_000);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.traces())
                .extracting(
                        row -> row.summary().rootActionType(),
                        row -> row.summary().outcome())
                .containsExactlyInAnyOrder(
                        tuple(RootActionType.HTTP_REQUEST, TraceOutcome.ERROR),
                        tuple(RootActionType.ASYNC_TASK, TraceOutcome.OK));
    }

    @Test
    void aTypeFilterSelectsRowsRatherThanTraces() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        assertThat(service.listTraces(listing()
                                .limit(10)
                                .rootActionType("http_request")
                                .build())
                        .traces())
                .extracting(row -> row.summary().rootActionType())
                .containsExactly(RootActionType.HTTP_REQUEST);
        assertThat(service.listTraces(
                                listing().limit(10).rootActionType("async_task").build())
                        .traces())
                .extracting(row -> row.summary().rootActionType())
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

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .extracting(row -> row.summary().traceId())
                .containsExactly("async1");
    }

    @Test
    void theLimitCountsRowsNotTraces() {
        addTraceWithAsyncWork("http1", 50, 1_000);

        assertThat(service.listTraces(listing().limit(1).rootActionType("*").build())
                        .traces())
                .hasSize(1);
        assertThat(service.listTraces(listing().limit(2).rootActionType("*").build())
                        .traces())
                .hasSize(2);
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

        TraceListResponse response = service.listTraces(
                listing().limit(10).bucket("slow").rootActionType("async_task").build());

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

        assertThat(service.listTraces(listing().limit(10).rootActionType("*").build())
                        .traces())
                .extracting(row -> row.summary().traceId(), row -> row.summary().rootActionType())
                .containsExactly(
                        tuple("http1", RootActionType.ASYNC_TASK),
                        tuple("http3", RootActionType.HTTP_REQUEST),
                        tuple("http2", RootActionType.HTTP_REQUEST),
                        tuple("http1", RootActionType.HTTP_REQUEST));
    }

    @Test
    void oneTracesAsyncRowsAreListedNewestFirstToo() {
        addTraceWithTwoAsyncTasks("http1", 50);

        assertThat(service.listTraces(listing()
                                .limit(10)
                                .rootActionType("*")
                                .view("full")
                                .build())
                        .traces())
                .extracting(row -> row.data().rootSpan().spanId())
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

        assertThat(service.listTraces(listing()
                                .limit(10)
                                .bucket("errors")
                                .rootActionType("*")
                                .build())
                        .traces())
                .extracting(row -> row.summary().traceId(), row -> row.summary().rootActionType())
                .containsExactly(
                        tuple("http1", RootActionType.HTTP_REQUEST),
                        tuple("http1", RootActionType.ASYNC_TASK),
                        tuple("http2", RootActionType.HTTP_REQUEST));
    }

    private static SpanData rootSpanWithoutTags(String traceId, String spanId, String name) {
        return span(spanId).in(traceId).named(name).build();
    }

    private List<String> listedIds(TraceListings.Builder listing) {
        return idsOf(service.listTraces(listing.build()));
    }

    /** Follows {@code next} one row per page for ten pages at most, so a cursor that never runs out fails instead of hanging. */
    private List<TraceResource> pagedOneByOne(TraceListings.Builder listing) {
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

    private static List<String> idsOf(TraceListResponse response) {
        return response.traces().stream().map(row -> row.summary().traceId()).toList();
    }

    private TraceInsightsService newService(TraceStore store) {
        return newService(store, List.of(), List.of());
    }

    private TraceInsightsService newService(
            TraceStore store, List<String> exclusions, List<String> applicationPackages) {
        return new TraceInsightsService(
                store,
                new MaskingEngine(),
                SqlFormatter.NONE,
                new PeekabootTracingProperties(),
                new StackTraceRules(exclusions, applicationPackages));
    }

    /** A single SERVER root span of the given duration. */
    private Spans.SpanBuilder rootSpan(String traceId, String name, Span.Kind kind, long durationMs) {
        return span("span-" + traceId).in(traceId).named(name).kind(kind).at(0, durationMs);
    }

    private void addTrace(String traceId, long durationMs, boolean hasError) {
        Spans.SpanBuilder root = rootSpan(traceId, "test-operation", Span.Kind.SERVER, durationMs);
        if (hasError) {
            root.exception("TestException", "Test error");
        }
        store.addSpan(root.build());
    }

    /** A complete HTTP request as the OpenTelemetry convention tags its server span. */
    private void addRequest(String traceId, String route, String path, int status, long startMs, long durationMs) {
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

    private void addRequest(String traceId, int status) {
        addRequest(traceId, "/fixtures/{id}", "/fixtures/" + traceId, status, 0, 10);
    }

    private static TraceListings.Builder listingFilteredBy(String parameter, String value) {
        return switch (parameter) {
            case "route" -> listing().route(value);
            case "path" -> listing().path(value);
            case "statusCode" -> listing().statusCode(value);
            case "outcome" -> listing().outcome(value);
            case "minDurationMs" -> listing().minDurationMs(value);
            case "since" -> listing().since(value);
            case "until" -> listing().until(value);
            default -> throw new IllegalArgumentException("No listing filter named " + parameter);
        };
    }

    private List<Tuple> rowsOf(TraceListings.Builder listing) {
        return service.listTraces(listing.build()).traces().stream()
                .map(row -> tuple(row.summary().traceId(), row.summary().rootActionType()))
                .toList();
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
                .exception("TestException", "Test error")
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
