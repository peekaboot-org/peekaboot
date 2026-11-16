package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.testsupport.TraceListings;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;

/** The rows {@link TraceInsightsService#listTraces} lists for a trace's async tasks. */
class TraceInsightsServiceAsyncRowsTest extends TraceInsightsServiceTestBase {

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

    /** An async row answers no request, whatever its entry span is tagged with. */
    @Test
    void anAsyncRowNamesNoPathWhateverItsEntrySpanCarries() {
        addRequest("t1", "/jobs", "/jobs", 200, 0, 50);
        store.addSpan(asyncTask("async-t1")
                .in("t1")
                .parent("span-t1")
                .at(40, 100)
                .tag("url.path", "/jobs")
                .build());

        assertThat(rowsOf(listing().rootActionType("*").path("/jobs")))
                .containsExactly(tuple("t1", RootActionType.HTTP_REQUEST));
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

    /** A verdict filter judges an async row on a summary, so the row's one full mapping is the page's. */
    @Test
    void aVerdictFilterInTheFullViewMapsAnAsyncRowInFullOnce() {
        List<String> formatted = new ArrayList<>();
        TraceInsightsService recordingService = serviceRecordingFormattedSqlInto(formatted);
        addTraceWithAsyncQuery("t1", "SELECT * FROM users WHERE id = ?");

        List<TraceResource> rows = recordingService
                .listTraces(listing()
                        .rootActionType("async_task")
                        .minDurationMs("0")
                        .view("full")
                        .build())
                .traces();

        assertThat(rows).extracting(row -> row.summary().rootActionType()).containsExactly(RootActionType.ASYNC_TASK);
        assertThat(formatted).containsExactly("SELECT * FROM users WHERE id = ?");
    }

    /** Each mapping of a row reads the trace's buckets once, so the bucket reads count the mappings. */
    @Test
    void aVerdictFilterInTheSummaryViewMapsAnAsyncRowOnce() {
        AtomicInteger bucketReads = new AtomicInteger();
        InMemoryTraceStore counting = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public Set<TraceBucket> bucketsOf(String traceId) {
                bucketReads.incrementAndGet();
                return super.bucketsOf(traceId);
            }
        };
        counting.addSpan(rootSpan("t1", "test-operation", Span.Kind.SERVER, 10).build());
        counting.addSpan(asyncTaskSpan("t1", 10, 50));

        List<TraceResource> rows = newService(counting)
                .listTraces(listing()
                        .rootActionType("async_task")
                        .minDurationMs("0")
                        .build())
                .traces();

        assertThat(rows).extracting(row -> row.summary().rootActionType()).containsExactly(RootActionType.ASYNC_TASK);
        assertThat(bucketReads).hasValue(1);
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

    /**
     * An async task that ends after a restart lists above the request that entered the bucket before it
     * ended. Without the run's raised orders every live stamp would sit at the restored maximum, and the
     * task's row would fall below that request.
     */
    @Test
    void anAsyncTaskThatEndsAfterARestartListsAboveTheRequestBeforeIt() {
        store.beginRun(Instant.parse("2026-10-30T08:00:00Z"));
        store.addSpan(
                span("a-root").in("a").kind(Span.Kind.SERVER).at(0, 5).order(9).build());
        store.beginRun(null);
        store.addSpan(span("c-root")
                .in("c")
                .kind(Span.Kind.SERVER)
                .at(100, 5)
                .order(1)
                .build());
        store.addSpan(span("d-root")
                .in("d")
                .kind(Span.Kind.SERVER)
                .at(110, 5)
                .order(2)
                .build());
        store.addSpan(asyncTask("c-task")
                .in("c")
                .parent("c-root")
                .at(102, 30)
                .order(3)
                .build());

        assertThat(service.listTraces(listing().limit(10).build()).traces())
                .extracting(
                        row -> row.summary().traceId(),
                        row -> row.summary().subtree() == null
                                ? null
                                : row.summary().subtree().rootSpanId())
                .containsExactly(tuple("c", "c-task"), tuple("d", null), tuple("c", null), tuple("a", null));
    }

    private List<Tuple> rowsOf(TraceListings.Builder listing) {
        return service.listTraces(listing.build()).traces().stream()
                .map(row -> tuple(row.summary().traceId(), row.summary().rootActionType()))
                .toList();
    }

    /** The hand-off of {@link #addTraceWithAsyncWork}, from a request that then failed while the work did not. */
    private void addFailedRequestWithAsyncWork(String traceId, long requestMs, long asyncMs) {
        store.addSpan(rootSpan(traceId, "test-operation", Span.Kind.SERVER, requestMs)
                .exception("TestException", "Test error")
                .build());
        store.addSpan(asyncTaskSpan(traceId, requestMs, asyncMs));
    }

    /** Background work whose triggering trace was evicted before it arrived. */
    private void addOrphanedAsyncWork(String traceId, long asyncMs) {
        store.addSpan(asyncTask("async-" + traceId)
                .in(traceId)
                .parent("gone")
                .at(0, asyncMs)
                .build());
    }
}
