package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;

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
}
