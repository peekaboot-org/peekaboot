package org.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduledTasksResponse(
        List<CronTask> cron, List<FixedTask> fixedDelay, List<FixedTask> fixedRate, List<Object> custom) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CronTask(
            String expression, TaskExecution lastExecution, TaskExecution nextExecution, RunnableTarget runnable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixedTask(
            Long initialDelay,
            Long interval,
            TaskExecution lastExecution,
            TaskExecution nextExecution,
            RunnableTarget runnable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskExecution(TaskExceptionInfo exception, String status, String time) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskExceptionInfo(String message, String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunnableTarget(String target) {}
}
