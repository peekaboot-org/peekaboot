package org.peekaboot.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class LatchesTest {

    /** The preemptive timeout only turns a hang into a failure; the bound itself is what is tested. */
    private static final Duration HANG = Duration.ofSeconds(5);

    @Test
    void returnsOnceTheLatchIsCountedDown() {
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();

        assertTimeoutPreemptively(HANG, () -> Latches.awaitQuietly(latch, Duration.ofMillis(50)));
    }

    /** A restore that never reaches its latch fails the test that waits for it instead of hanging the class. */
    @Test
    void failsALatchStillClosedPastTheBound() {
        assertTimeoutPreemptively(
                HANG,
                () -> assertThatThrownBy(() -> Latches.awaitQuietly(new CountDownLatch(1), Duration.ofMillis(50)))
                        .isInstanceOf(AssertionError.class)
                        .hasMessage("The latch was still closed after 50 ms."));
    }

    @Test
    void anInterruptEndsTheWaitAndStaysOnTheThread() {
        Thread.currentThread().interrupt();

        Latches.awaitQuietly(new CountDownLatch(1), Duration.ofSeconds(5));

        assertThat(Thread.interrupted()).isTrue();
    }
}
