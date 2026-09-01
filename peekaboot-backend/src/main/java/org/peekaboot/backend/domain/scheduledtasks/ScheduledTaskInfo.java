package org.peekaboot.backend.domain.scheduledtasks;

import java.time.Instant;

/**
 * One @Scheduled method as the dashboard's Scheduled Tasks tab lists it.
 *
 * @param schedule the cron expression; null for fixed-rate/fixed-delay tasks, whose
 *                 interval travels as {@code intervalMs} and is formatted by the frontend
 * @param intervalMs the fixed rate/delay in milliseconds; null for cron tasks
 */
public record ScheduledTaskInfo(
        String target,
        TaskType type,
        String schedule,
        String scheduleDescription,
        Long intervalMs,
        Instant lastExecution,
        TaskExecutionStatus lastStatus,
        String lastException,
        Instant nextExecution) {}
