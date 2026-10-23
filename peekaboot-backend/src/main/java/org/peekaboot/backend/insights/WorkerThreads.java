package org.peekaboot.backend.insights;

import java.time.Duration;
import java.util.Collection;

/** How the insights background threads are stopped: interrupted together, then joined one by one. */
public final class WorkerThreads {

    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(2);

    private WorkerThreads() {}

    /**
     * Interrupts every thread, then waits up to two seconds for each. An interrupt
     * of the caller ends the wait and is kept on its flag.
     */
    public static void stopAndJoin(Collection<Thread> threads) {
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(JOIN_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
