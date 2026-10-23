package org.peekaboot.backend.testsupport;

import java.util.concurrent.CountDownLatch;

/** Latch waits for code that cannot throw a checked exception, such as a gauge a test parks inside. */
public final class Latches {

    private Latches() {}

    /** Waits for {@code latch}; an interrupt ends the wait and stays on the thread's flag. */
    public static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
