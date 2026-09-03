package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntervalBoundaryTest {

    @Test
    void wakesAtTheNextMultipleOfTheIntervalAndReturnsIt() throws InterruptedException {
        long before = System.currentTimeMillis();

        long boundary = IntervalBoundary.sleepUntilNext(40, 0);

        assertThat(boundary % 40).isZero();
        assertThat(boundary).isGreaterThan(before);
        assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(boundary);
    }

    /** An aggregation level waits past the boundary so the finer level's write has landed. */
    @Test
    void theOffsetDelaysTheWakeUpButNotTheBoundaryItReports() throws InterruptedException {
        long boundary = IntervalBoundary.sleepUntilNext(40, 15);

        assertThat(boundary % 40).isZero();
        assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(boundary + 15);
    }
}
