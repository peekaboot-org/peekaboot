package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TraceOutcomeTest {

    /** A root that arrived settles the outcome, whatever became of its run; without one, the run decides. */
    @ParameterizedTest
    @CsvSource({
        "false, false, false, IN_PROGRESS",
        "false, false, true,  IN_PROGRESS",
        "false, true,  false, INTERRUPTED",
        "false, true,  true,  INTERRUPTED",
        "true,  false, false, OK",
        "true,  false, true,  ERROR",
        "true,  true,  false, OK",
        "true,  true,  true,  ERROR"
    })
    void anOutcomeFollowsCompletenessThenTheRunThenFailure(
            boolean complete, boolean interrupted, boolean failed, TraceOutcome expected) {
        assertThat(TraceOutcome.of(complete, interrupted, failed)).isEqualTo(expected);
    }
}
