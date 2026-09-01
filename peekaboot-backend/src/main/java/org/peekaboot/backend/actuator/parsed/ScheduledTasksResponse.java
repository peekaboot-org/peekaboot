package org.peekaboot.backend.actuator.parsed;

import java.time.Instant;
import java.util.List;

public record ScheduledTasksResponse(List<CronTask> cron, List<FixedTask> fixedDelay, List<FixedTask> fixedRate) {

    public record CronTask(
            String expression, TaskExecution lastExecution, TaskExecution nextExecution, RunnableTarget runnable) {}

    public record FixedTask(
            Long interval, TaskExecution lastExecution, TaskExecution nextExecution, RunnableTarget runnable) {}

    public record TaskExecution(TaskExceptionInfo exception, String status, Instant time) {}

    public record TaskExceptionInfo(String message, String type) {}

    public record RunnableTarget(String target) {}
}
