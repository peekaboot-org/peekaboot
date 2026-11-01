package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import tools.jackson.databind.JsonNode;

/** What the trace API says about a whole trace, its buckets and its outcome and why it got them. */
class TraceSummaryIT extends TestingAppITBase {

    /** A handler that answers 503 without throwing fails its request all the same. */
    @Test
    void aServerErrorWithoutAnExceptionIsListedInErrors() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(503));

        traces.awaitTraceInBucket("errors", traceId);
    }

    /**
     * A 4xx is the caller's mistake. The store classifies a trace a moment after its root is
     * stored, so a race can let this check pass early but never fail it.
     */
    @Test
    void aClientErrorStaysOutOfErrors() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(404));

        traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }

    @Test
    void aClientErrorIsAWarningOnTheRoot() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(409));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode issue = SpanTree.issuesOfType(trace, "CLIENT_ERROR").getFirst();
        assertThat(issue.path("spanId").asString()).isEqualTo(SpanTree.spanId(trace.path("rootSpan")));
        assertThat(issue.path("value").asInt()).isEqualTo(409);
        assertThat(issue.path("unit").asString()).isEqualTo("status");
        assertThat(trace.path("outcome").asString()).isEqualTo("OK");
    }

    /**
     * Spring resolves a {@code ResponseStatusException} inside the dispatcher. If it marked the server
     * span ERROR for it, every 404 would fail its trace and fill Errors.
     */
    @Test
    void aNotFoundRaisedAsAnExceptionIsAClientErrorNotAFailure() {
        String traceId = traces.get("/persons/987654321");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.issuesOfType(trace, "CLIENT_ERROR")).hasSize(1);
        assertThat(trace.path("outcome").asString()).isEqualTo("OK");
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }
}
