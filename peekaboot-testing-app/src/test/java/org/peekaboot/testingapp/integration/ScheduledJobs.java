package org.peekaboot.testingapp.integration;

import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Runs a {@code @Scheduled} method the way Spring's {@code TaskScheduler} does, instead of
 * waiting on its timer (the sample app's jobs fire every two minutes to hourly - far past
 * what an integration test should block on).
 *
 * <p>The {@link ScheduledTaskHolder} bean (Spring's {@code ScheduledAnnotationBeanPostProcessor})
 * exposes the {@link ScheduledTask} registered for every {@code @Scheduled} method; running its
 * {@code Runnable} is the real production code path, not a stand-in for it - it builds the same
 * {@code ScheduledTaskObservationContext} and sets the same {@code code.function}/{@code
 * code.namespace} tags that classify the trace root {@code SCHEDULED_JOB}, exactly as a live
 * scheduler firing it would.
 */
public final class ScheduledJobs {

    private ScheduledJobs() {}

    public static void run(ScheduledTaskHolder scheduledTasks, Class<?> beanClass, String methodName) {
        // Task#toString() delegates down to the underlying ScheduledMethodRunnable's
        // toString() ("<declaringClass>.<method>"); getTask() itself wraps the runnable
        // in an outcome-tracking decorator, so matching on the runnable's type directly
        // isn't an option.
        String taskDescription = beanClass.getName() + "." + methodName;
        scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> taskDescription.equals(task.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(taskDescription + " is not registered as a scheduled task"))
                .getRunnable()
                .run();
    }
}
