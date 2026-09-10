package org.peekaboot.testingapp.integration;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Runs a {@code @Scheduled} method the way Spring's {@code TaskScheduler} does, instead of
 * waiting on its timer (the sample app's jobs fire every two minutes to hourly - far past
 * what an integration test should block on), and answers with the trace id of that run.
 *
 * <p>The {@link ScheduledTaskHolder} bean ({@code ScheduledAnnotationBeanPostProcessor})
 * exposes the {@link ScheduledTask} registered for every {@code @Scheduled} method; running its
 * {@code Runnable} is the real production code path, not a stand-in for it - it builds the same
 * {@code ScheduledTaskObservationContext} and sets the same {@code code.function}/{@code
 * code.namespace} tags that classify the trace root {@code SCHEDULED_JOB}, exactly as a live
 * scheduler firing it would.
 *
 * <p>A scheduled job answers no request, so it carries no {@code Server-Timing} header for a
 * caller to read its trace id off the way {@link TraceApiClient} does. The id exists only on
 * the span Micrometer's tracing handler creates for the observation, so {@link #traceIdRecorder()}
 * reads it from the observation context and parks it on the thread the job ran on -
 * {@link #run} then picks it up, because the runnable runs inline on the caller's own thread.
 * Two classes firing the same job against the shared store is the case this exists for: each
 * one waits for the run it fired rather than for whichever trace names that job.
 */
public final class ScheduledJobs {

    private static final ThreadLocal<String> RUN_TRACE_ID = new ThreadLocal<>();

    private ScheduledJobs() {}

    /** Fires the job on the calling thread and returns the id of the trace that run produced. */
    public static String run(ScheduledTaskHolder scheduledTasks, Class<?> beanClass, String methodName) {
        // Task#toString() delegates down to the underlying ScheduledMethodRunnable's
        // toString() ("<declaringClass>.<method>"); getTask() itself wraps the runnable
        // in an outcome-tracking decorator, so matching on the runnable's type directly
        // isn't an option.
        String taskDescription = beanClass.getName() + "." + methodName;
        Runnable runnable = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> taskDescription.equals(task.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(taskDescription + " is not registered as a scheduled task"))
                .getRunnable();

        RUN_TRACE_ID.remove();
        runnable.run();
        String traceId = RUN_TRACE_ID.get();
        RUN_TRACE_ID.remove();
        if (traceId == null) {
            throw new AssertionError(taskDescription + " ran without a traced scheduled-task observation; this "
                    + "context is missing the recorder DeferredSchedulingConfig registers");
        }
        return traceId;
    }

    /**
     * The handler that makes {@link #run} able to name its own trace.
     *
     * <p>Declared as a bean by {@code DeferredSchedulingConfig}, so every context the suite
     * fires jobs in carries it.
     */
    public static ObservationHandler<ScheduledTaskObservationContext> traceIdRecorder() {
        return new TraceIdRecorder();
    }

    private static final class TraceIdRecorder implements ObservationHandler<ScheduledTaskObservationContext> {

        /**
         * Read on stop rather than on start, so it holds whatever order the registry calls its
         * handlers in: by stop, the tracing handler has put the span it created in the context.
         */
        @Override
        public void onStop(ScheduledTaskObservationContext context) {
            TracingObservationHandler.TracingContext tracing =
                    context.get(TracingObservationHandler.TracingContext.class);
            if (tracing != null && tracing.getSpan() != null) {
                RUN_TRACE_ID.set(tracing.getSpan().context().traceId());
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ScheduledTaskObservationContext;
        }
    }
}
