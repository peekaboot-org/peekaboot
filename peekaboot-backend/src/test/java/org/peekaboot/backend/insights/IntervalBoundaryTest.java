package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntervalBoundaryTest {

    /** Records the one sleep the boundary asks for instead of sleeping. */
    private static final class RecordingSleeper implements IntervalBoundary.Sleeper {
        long sleptMs = -1;

        @Override
        public void sleep(long millis) {
            sleptMs = millis;
        }
    }

    @Test
    void sleepsToTheNextMultipleOfTheIntervalAndReturnsIt() throws InterruptedException {
        RecordingSleeper sleeper = new RecordingSleeper();
        IntervalBoundary boundary = new IntervalBoundary(() -> 10_030, sleeper);

        assertThat(boundary.sleepUntilNext(10_000, 0)).isEqualTo(20_000);
        assertThat(sleeper.sleptMs).isEqualTo(9_970);
    }

    @Test
    void reportsTheClockItSchedulesBy() {
        assertThat(new IntervalBoundary(() -> 10_030, new RecordingSleeper()).now())
                .isEqualTo(10_030);
    }

    /** A clock sitting on a boundary has already been woken for it; the next one is a whole interval away. */
    @Test
    void aClockOnTheBoundaryWaitsForTheFollowingOne() throws InterruptedException {
        RecordingSleeper sleeper = new RecordingSleeper();
        IntervalBoundary boundary = new IntervalBoundary(() -> 20_000, sleeper);

        assertThat(boundary.sleepUntilNext(10_000, 0)).isEqualTo(30_000);
        assertThat(sleeper.sleptMs).isEqualTo(10_000);
    }

    /** An aggregation level wakes past the boundary so the finer level's write has landed, but reports the boundary itself. */
    @Test
    void theOffsetDelaysTheWakeUpButNotTheBoundaryItReports() throws InterruptedException {
        RecordingSleeper sleeper = new RecordingSleeper();
        IntervalBoundary boundary = new IntervalBoundary(() -> 10_030, sleeper);

        assertThat(boundary.sleepUntilNext(60_000, 5_000)).isEqualTo(60_000);
        assertThat(sleeper.sleptMs).isEqualTo(54_970);
    }
}
