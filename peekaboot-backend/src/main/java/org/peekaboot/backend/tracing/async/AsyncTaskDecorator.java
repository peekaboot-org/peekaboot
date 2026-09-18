package org.peekaboot.backend.tracing.async;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.RunnableScheduledFuture;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;

/**
 * Observes work handed to Spring's task executors, so a task dispatched across a thread
 * hand-off shows up as background work rather than as a trace of its own.
 *
 * <p>This is instrumentation Peekaboot adds, not instrumentation it consumes. The observation
 * reaches every exporter the application has configured, not only Peekaboot's store, and
 * {@code peekaboot.tracing.async} turns it off.
 *
 * <p>Two things this deliberately does not do. It does not observe a task dispatched with no
 * observation in scope: the span exists to continue a propagated trace, and without one it
 * would be a rootless orphan. And it does not observe scheduled execution - one
 * {@code TaskDecorator} bean is consumed by both the executor and the scheduler builders, and
 * {@code ThreadPoolTaskScheduler} decorates the scheduled future rather than the user task, so
 * without the type test below Peekaboot's span would become the parent of Spring's own
 * {@code tasks.scheduled.execution} and every scheduled job would read as an async task.
 * Scheduled execution cannot be recognised from inside the wrapper, because
 * {@code ScheduledMethodRunnable} opens that observation itself, further down the stack.
 */
public class AsyncTaskDecorator implements TaskDecorator {

    private final ObservationRegistry observationRegistry;

    public AsyncTaskDecorator(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        if (isScheduledExecution(runnable)) {
            return runnable;
        }
        return () -> observeIfContinuingATrace(runnable);
    }

    private void observeIfContinuingATrace(Runnable runnable) {
        if (observationRegistry.getCurrentObservation() == null) {
            runnable.run();
            return;
        }
        Observation.createNotStarted(AsyncTaskMarker.OBSERVATION_NAME, observationRegistry)
                .contextualName(AsyncTaskMarker.CONTEXTUAL_NAME)
                .lowCardinalityKeyValue(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE)
                .highCardinalityKeyValue(
                        AsyncTaskMarker.THREAD_TAG_KEY, Thread.currentThread().getName())
                .observe(runnable);
    }

    /**
     * The two shapes the scheduling paths hand to a decorator: a scheduled future from
     * {@code ThreadPoolTaskScheduler}'s {@code decorateTask} hook, and an error-handling
     * wrapper from {@code SimpleAsyncTaskScheduler}. Neither ever reaches a plain task executor.
     */
    private static boolean isScheduledExecution(Runnable runnable) {
        return runnable instanceof RunnableScheduledFuture<?> || runnable instanceof DelegatingErrorHandlingRunnable;
    }
}
