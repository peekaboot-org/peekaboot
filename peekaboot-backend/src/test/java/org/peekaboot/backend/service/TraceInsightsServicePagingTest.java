package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;

/** The limit and the cursor of {@link TraceInsightsService#listTraces}. */
class TraceInsightsServicePagingTest extends TraceInsightsServiceTestBase {

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
    void refusesAnOutOfRangeLimitWhileTracingIsOff() {
        assertThatThrownBy(() -> newService(null).listTraces(listing().limit(0).build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("limit"));
    }

    @Test
    void refusesAFullViewLimitAboveItsCapWhileTracingIsOff() {
        assertThatThrownBy(() -> newService(null)
                        .listTraces(listing().view("Full").limit(101).build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("limit"));
    }

    /** The default limit is a full page too, so a request that names none is served. */
    @Test
    void aFullViewWithoutALimitListsAHundred() {
        for (int i = 0; i < 101; i++) {
            addTrace("trace" + i, 10, false);
        }

        TraceListResponse response = service.listTraces(listing().view("full").build());

        assertThat(response.traces()).hasSize(100);
        assertThat(response.next()).isNotNull();
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

    /** The next page of a store started again is refused, never read as a position it now names. */
    @Test
    void aCursorFromAnotherNumberingIsRefused() {
        addTrace("t1", 10, false);
        addTrace("t2", 10, false);
        String next = service.listTraces(listing().limit(1).build()).next();
        InMemoryTraceStore restarted = TraceStores.withDefaults();
        restarted.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 10).build());
        restarted.addSpan(rootSpan("t2", "test-operation", Span.Kind.SERVER, 10).build());

        assertThatThrownBy(() -> newService(restarted)
                        .listTraces(listing().limit(1).cursor(next).build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("cursor");
                    assertThat(e.problem().detail()).contains("before the application restarted");
                });
    }

    /** With tracing off there is no numbering to compare, so a cursor of the right shape asks for an empty page. */
    @Test
    void aWellFormedCursorWithTracingOffListsNothing() {
        addTrace("t1", 10, false);
        addTrace("t2", 10, false);
        String next = service.listTraces(listing().limit(1).build()).next();

        assertThat(newService(null).listTraces(listing().cursor(next).build()).traces())
                .isEmpty();
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

    /** All three async rows are judged and one is on the page. Only a full mapping lays a statement out. */
    @Test
    void aVerdictFilterInTheFullViewFormatsOnlyThePagesRows() {
        List<String> formatted = new ArrayList<>();
        TraceInsightsService recordingService = serviceRecordingFormattedSqlInto(formatted);
        addTraceWithAsyncQuery("t1", "SELECT 1");
        addTraceWithAsyncQuery("t2", "SELECT 2");
        addTraceWithAsyncQuery("t3", "SELECT 3");

        TraceListResponse response = recordingService.listTraces(listing()
                .rootActionType("async_task")
                .minDurationMs("0")
                .view("full")
                .limit(1)
                .build());

        assertThat(idsOf(response)).containsExactly("t3");
        assertThat(response.filteredBucketCounts()).isEqualTo(new BucketCounts(3, 0, 0));
        assertThat(formatted).containsExactly("SELECT 3");
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
}
