package org.peekaboot.backend.mapper.actuator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.peekaboot.backend.actuator.parsed.ScheduledTasksResponse;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTaskInfo;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTasksInfo;
import org.peekaboot.backend.domain.scheduledtasks.TaskExecutionStatus;
import org.peekaboot.backend.domain.scheduledtasks.TaskType;

public class ScheduledTasksMapper {

    private final CronDescriber cronDescriber = new CronDescriber();

    public ScheduledTasksInfo map(ScheduledTasksResponse response, Locale locale) {
        if (response == null) {
            return new ScheduledTasksInfo(List.of(), 0, 0, 0);
        }

        var cronTasks = orEmpty(response.cron());
        var fixedDelayTasks = orEmpty(response.fixedDelay());
        var fixedRateTasks = orEmpty(response.fixedRate());

        List<ScheduledTaskInfo> tasks = new ArrayList<>();
        for (var cron : cronTasks) {
            tasks.add(mapCronTask(cron, locale));
        }
        for (var fixed : fixedDelayTasks) {
            tasks.add(mapFixedTask(fixed, TaskType.FIXED_DELAY));
        }
        for (var fixed : fixedRateTasks) {
            tasks.add(mapFixedTask(fixed, TaskType.FIXED_RATE));
        }

        tasks.sort(Comparator.comparing(ScheduledTaskInfo::type).thenComparing(ScheduledTaskInfo::target));

        return new ScheduledTasksInfo(tasks, cronTasks.size(), fixedDelayTasks.size(), fixedRateTasks.size());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }

    private ScheduledTaskInfo mapCronTask(ScheduledTasksResponse.CronTask cron, Locale locale) {
        return new ScheduledTaskInfo(
                extractTarget(cron.runnable()),
                TaskType.CRON,
                cron.expression(),
                cronDescriber.describe(cron.expression(), locale),
                null,
                timeOf(cron.lastExecution()),
                parseStatus(cron.lastExecution()),
                parseException(cron.lastExecution()),
                timeOf(cron.nextExecution()));
    }

    private ScheduledTaskInfo mapFixedTask(ScheduledTasksResponse.FixedTask fixed, TaskType type) {
        return new ScheduledTaskInfo(
                extractTarget(fixed.runnable()),
                type,
                null,
                null,
                fixed.interval(),
                timeOf(fixed.lastExecution()),
                parseStatus(fixed.lastExecution()),
                parseException(fixed.lastExecution()),
                timeOf(fixed.nextExecution()));
    }

    private String extractTarget(ScheduledTasksResponse.RunnableTarget runnable) {
        return runnable != null ? runnable.target() : "unknown";
    }

    private Instant timeOf(ScheduledTasksResponse.TaskExecution execution) {
        return execution != null ? execution.time() : null;
    }

    private TaskExecutionStatus parseStatus(ScheduledTasksResponse.TaskExecution execution) {
        if (execution == null) {
            return TaskExecutionStatus.PENDING;
        }
        return TaskExecutionStatus.fromString(execution.status());
    }

    private String parseException(ScheduledTasksResponse.TaskExecution execution) {
        if (execution == null || execution.exception() == null) {
            return null;
        }
        var ex = execution.exception();
        if (ex.type() != null && ex.message() != null) {
            return ex.type() + ": " + ex.message();
        }
        return ex.message() != null ? ex.message() : ex.type();
    }
}
