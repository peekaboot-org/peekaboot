package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.testsupport.TraceListings.listing;

import io.micrometer.tracing.Span;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.problem.ApiProblem;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;

/**
 * The buckets, the counts and the view of {@link TraceInsightsService#listTraces}, and what it
 * answers with tracing off.
 */
class TraceInsightsServiceListingTest extends TraceInsightsServiceTestBase {

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

    /** A full row with nothing to list still says "none" as a list, never as null. */
    @Test
    void aFullRowCarriesEmptyLogAndQueryListsRatherThanNulls() {
        addTrace("trace1", 100, false);

        TraceListResponse response =
                service.listTraces(listing().limit(10).view("full").build());

        assertThat(response.traces().getFirst().data().logs()).isEmpty();
        assertThat(response.traces().getFirst().data().queries()).isEmpty();
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

    /** The listing leaves a fragment out, so here a failing span lands in a trace already complete. */
    @Test
    void aListedRowIsMappedBeforeItsBucketsAreRead() {
        InMemoryTraceStore landing = TraceStores.landingAfterTheFirstBucketRead(span("late")
                .in("t1")
                .parent("root")
                .named("charge")
                .at(5, 5)
                .exception("IllegalStateException", "boom")
                .build());
        landing.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 20).build());

        TraceSummary summary = newService(landing)
                .listTraces(listing().build())
                .traces()
                .getFirst()
                .summary();

        assertThat(summary.outcome()).isEqualTo(TraceOutcome.OK);
        assertThat(summary.buckets()).isEmpty();
    }
}
