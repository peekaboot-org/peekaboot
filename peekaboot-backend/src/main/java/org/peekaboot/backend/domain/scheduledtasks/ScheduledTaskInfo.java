package org.peekaboot.backend.domain.scheduledtasks;

import java.time.Instant;

/**
 * One scheduled task as the dashboard's Scheduled Tasks tab lists it.
 *
 * @param schedule the cron expression, or a custom trigger's own description, masked; null
 *                 for fixed-rate/fixed-delay tasks, whose interval travels as
 *                 {@code intervalMs} and is formatted by the frontend
 * @param scheduleDescription the cron expression in words; null for every other task
 * @param intervalMs the fixed rate/delay in milliseconds; null for cron and custom-trigger tasks
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
