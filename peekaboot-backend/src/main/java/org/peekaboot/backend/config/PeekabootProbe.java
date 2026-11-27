package org.peekaboot.backend.config;

/**
 * Marks the threads that run Peekaboot's own probes, such as the health checks it times, so
 * their work stays out of the application's observations.
 */
public final class PeekabootProbe {

    // A scoped value cannot outlive run(), where a ThreadLocal would leak onto a reused thread.
    private static final ScopedValue<Boolean> RUNNING = ScopedValue.newInstance();

    private PeekabootProbe() {}

    /** Runs the probe on the calling thread; observations it starts are rejected. */
    public static void run(Runnable probe) {
        ScopedValue.where(RUNNING, true).run(probe);
    }

    public static boolean isRunning() {
        return RUNNING.isBound();
    }
}
