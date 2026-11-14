package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.Latches.awaitQuietly;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundLoadTest {

    /** Long past anything a load in these tests takes; a wait that reaches it fails its test. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    private final BackgroundLoad load = new BackgroundLoad();

    @Test
    void waitersAreReleasedWhenTheLoadEnds() {
        CountDownLatch loading = new CountDownLatch(1);
        load.begin("background-load-under-test", () -> awaitQuietly(loading));
        assertThat(load.await(Duration.ZERO)).isFalse();

        loading.countDown();

        assertThat(load.await(BOUND)).isTrue();
        assertThat(load.await()).isTrue();
    }

    /** An Error is what a catch of the load's own would miss, an OutOfMemoryError from a pathological file, say. */
    @Test
    void waitersAreReleasedWhenTheLoadThrows() {
        assertThatThrownBy(() -> load.run(() -> {
                    throw new OutOfMemoryError("simulated");
                }))
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessage("simulated");

        assertThat(load.await(Duration.ZERO)).isTrue();
    }

    @Test
    void aWaitThatTimesOutAnswersFalse() {
        assertThat(load.await(Duration.ZERO)).isFalse();
    }

    @Test
    void anInterruptedWaitAnswersFalseAndKeepsTheFlag() {
        Thread.currentThread().interrupt();

        boolean arrived = load.await(BOUND);
        // Reads the flag and clears it, so it does not reach the next test.
        boolean flagKept = Thread.interrupted();

        assertThat(arrived).isFalse();
        assertThat(flagKept).isTrue();
    }

    @Test
    void anInterruptedWaitWithoutLimitAnswersFalseAndKeepsTheFlag() {
        Thread.currentThread().interrupt();

        boolean arrived = load.await();
        // Reads the flag and clears it, so it does not reach the next test.
        boolean flagKept = Thread.interrupted();

        assertThat(arrived).isFalse();
        assertThat(flagKept).isTrue();
    }

    @Test
    void aLoadWithNothingToReadReleasesItsWaiters() {
        load.markLoaded();

        assertThat(load.await(Duration.ZERO)).isTrue();
    }

    /** The caller is the application's startup, which must not wait for a file. */
    @Test
    void aLoadBegunRunsOnANamedVirtualThread() {
        AtomicReference<Thread> loader = new AtomicReference<>();

        load.begin("background-load-under-test", () -> loader.set(Thread.currentThread()));

        assertThat(load.await(BOUND)).isTrue();
        assertThat(loader.get().isVirtual()).isTrue();
        assertThat(loader.get().getName()).isEqualTo("background-load-under-test");
    }

    @Test
    void aLoadRunHoldsItsCallerUntilItEnds() {
        AtomicReference<Thread> loader = new AtomicReference<>();

        load.run(() -> loader.set(Thread.currentThread()));

        assertThat(loader).hasValue(Thread.currentThread());
        assertThat(load.await(Duration.ZERO)).isTrue();
    }
}
