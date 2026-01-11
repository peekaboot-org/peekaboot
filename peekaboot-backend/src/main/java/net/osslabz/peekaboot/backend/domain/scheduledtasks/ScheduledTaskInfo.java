package net.osslabz.peekaboot.backend.domain.scheduledtasks;

import java.time.Instant;

public record ScheduledTaskInfo(
    String target,
    TaskType type,
    String schedule,
    String scheduleDescription,
    Long intervalMs,
    Instant lastExecution,
    TaskExecutionStatus lastStatus,
    String lastException,
    Instant nextExecution
) {}
