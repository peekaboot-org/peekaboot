package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.testsupport.TraceListings;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;

/** The filters of {@link TraceInsightsService#listTraces} and the counts they narrow. */
class TraceInsightsServiceFilterTest extends TraceInsightsServiceTestBase {

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

    /** A filter on the stored path would confirm a guess at what the response hides. */
    @Test
    void thePathFilterReadsThePathAsServed() {
        addRequest("t1", "/reset", "/reset;token=abc123", 200, 0, 10);

        assertThat(listedIds(listing().path("/reset;token=abc"))).isEmpty();
        assertThat(listedIds(listing().path("/reset;token=***"))).containsExactly("t1");
    }

    @Test
    void aRowThatNamesNoPathMatchesNoPathFilter() {
        addScheduledJobTrace("job", 10);
        addTraceWithAsyncWork("req", 10, 50);
        store.addSpan(rootSpan("bare", "test-operation", Span.Kind.SERVER, 10).build());

        assertThat(listedIds(listing().rootActionType("*").path("/"))).isEmpty();
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
                "route | orders",
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

    /** Only a request that filters nothing at all - every type, any operation - skips the extra bucket passes. */
    @Test
    void filteredBucketCountsAreOmittedWhenEveryTypeIsAskedFor() {
        addTrace("trace1", 100, false);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootActionType("*").build());

        assertThat(response.filteredBucketCounts()).isNull();
    }

    /** The Scheduled Tasks tab links to a job's runs by the target Actuator names, which no span name holds. */
    @ParameterizedTest
    @ValueSource(strings = {"org.peekaboot.example.Scheduler.fixedDelay", "ORG.PEEKABOOT.EXAMPLE.SCHEDULER.FIXEDDELAY"})
    void theRootOperationFilterMatchesAScheduledJobsFullyQualifiedTarget(String target) {
        addScheduledJobTrace(
                "trace1", "task scheduler.fixedDelay", "org.peekaboot.example.Scheduler", "fixedDelay", 100);
        addScheduledJobTrace("trace2", "task scheduler.fixedRate", "org.peekaboot.example.Scheduler", "fixedRate", 100);

        TraceListResponse response =
                service.listTraces(listing().limit(10).rootOperation(target).build());

        assertThat(response.traces()).extracting(row -> row.summary().traceId()).containsExactly("trace1");
    }

    @Test
    void aDottedRootOperationMatchesNoSpanOnItsLastTwoSegments() {
        addTraceWithOperation("t1", "task scheduler.fixedDelay", 100);

        TraceListResponse response = service.listTraces(listing()
                .limit(10)
                .rootOperation("org.peekaboot.example.Scheduler.fixedDelay")
                .build());

        assertThat(response.traces()).isEmpty();
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
     * The outcome filter reads the listing facts, which know the run ended. A trace with spans but no
     * root never lists, so these two hold a log each, as an in-progress trace that lists does.
     */
    @Test
    void theOutcomeFilterTellsAnInterruptedTraceFromOneInProgress() {
        store.beginRun(Instant.parse("2026-10-30T08:00:00Z"));
        store.addLog(log("cut-off").build());
        store.beginRun(null);
        store.addLog(log("running").build());

        assertThat(idsOf(service.listTraces(listing().outcome("interrupted").build())))
                .containsExactly("cut-off");
        assertThat(idsOf(service.listTraces(listing().outcome("in_progress").build())))
                .containsExactly("running");
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

    private void addScheduledJobTrace(String traceId, long durationMs) {
        addScheduledJobTrace(
                traceId,
                "task orderReconciler.reconcileOrders",
                "org.peekaboot.example.OrderReconciler",
                "reconcileOrders",
                durationMs);
    }

    /** A run as Spring's scheduler observes it: named after the bean, tagged with the target's class and method. */
    private void addScheduledJobTrace(
            String traceId, String operationName, String targetClass, String method, long durationMs) {
        store.addSpan(rootSpan(traceId, operationName, null, durationMs)
                .tags(Map.of("code.function", method, "code.namespace", targetClass))
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
}
