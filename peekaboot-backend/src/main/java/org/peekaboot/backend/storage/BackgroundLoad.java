package org.peekaboot.backend.storage;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A store's one read of what an earlier run left, kept off the startup path, and the wait
 * for it. The waiters are released however the read ends: a wait never released costs each
 * of them its full timeout, on a thread the host application owns. Thread-safe.
 */
public final class BackgroundLoad {

    private final CountDownLatch ended = new CountDownLatch(1);

    /** Runs {@code load} on a virtual thread named {@code threadName}; returns immediately. */
    public void begin(String threadName, Runnable load) {
        Thread.ofVirtual().name(threadName).start(() -> run(load));
    }

    /** Runs {@code load} on the caller's thread. What it throws reaches the caller, after the waiters are released. */
    public void run(Runnable load) {
        try {
            load.run();
        } finally {
            ended.countDown();
        }
    }

    /** For a store with nothing to read. */
    public void markLoaded() {
        ended.countDown();
    }

    /** Whether the load ended within {@code timeout}. An interrupt answers false and stays on the thread's flag. */
    public boolean await(Duration timeout) {
        try {
            return ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits however long the load takes. An interrupt answers false and stays on the thread's flag. */
    public boolean await() {
        try {
            ended.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
