package org.peekaboot.backend.testsupport;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Latch waits for code that cannot throw a checked exception, such as a gauge a test parks inside. */
public final class Latches {

    /** Awaitility's default, long past anything a latch in these tests waits for. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    private Latches() {}

    /**
     * Waits for {@code latch} up to {@link #BOUND}. A latch still closed then fails with an
     * {@link AssertionError}, so a stuck wait fails its test instead of hanging it, and a thread a
     * failed test never released goes on. An interrupt ends the wait and stays on the thread's flag.
     */
    public static void awaitQuietly(CountDownLatch latch) {
        awaitQuietly(latch, BOUND);
    }

    static void awaitQuietly(CountDownLatch latch, Duration bound) {
        try {
            if (!latch.await(bound.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The latch was still closed after " + bound.toMillis() + " ms.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
