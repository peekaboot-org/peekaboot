package org.peekaboot.backend.tracing.async;

import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

/**
 * The {@code async task} observation this thread is running, so a failure Spring catches inside
 * the task can still be recorded on it. Kept apart from the registry's current observation,
 * which can be an application's own, a listener's, or one propagated from another task.
 */
final class RunningAsyncTask {

    private static final ThreadLocal<@Nullable Observation> RUNNING = new ThreadLocal<>();

    private RunningAsyncTask() {}

    /** Restores rather than clears: under CallerRunsPolicy a task runs nested inside another on one thread. */
    static void run(Observation task, Runnable runnable) {
        Observation enclosing = RUNNING.get();
        RUNNING.set(task);
        try {
            runnable.run();
        } finally {
            if (enclosing == null) {
                RUNNING.remove();
            } else {
                RUNNING.set(enclosing);
            }
        }
    }

    /** Does nothing on a thread that is not running a decorated task. */
    static void recordFailure(Throwable failure) {
        Observation task = RUNNING.get();
        if (task != null) {
            task.error(failure);
        }
    }
}
