package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.ScheduledTasksResponse;
import org.peekaboot.backend.domain.scheduledtasks.*;
import org.peekaboot.backend.service.CronDescriptionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ScheduledTasksMapper {

    private final CronDescriptionService cronDescriptionService;

    public ScheduledTasksMapper(CronDescriptionService cronDescriptionService) {
        this.cronDescriptionService = cronDescriptionService;
    }

    public ScheduledTasksInfo map(ScheduledTasksResponse response, Locale locale) {
        if (response == null) {
            return new ScheduledTasksInfo(List.of(), 0, 0, 0);
        }

        List<ScheduledTaskInfo> tasks = new ArrayList<>();

        if (response.cron() != null) {
            for (var cron : response.cron()) {
                tasks.add(mapCronTask(cron, locale));
            }
        }

        if (response.fixedDelay() != null) {
            for (var fixed : response.fixedDelay()) {
                tasks.add(mapFixedTask(fixed, TaskType.FIXED_DELAY));
            }
        }

        if (response.fixedRate() != null) {
            for (var fixed : response.fixedRate()) {
                tasks.add(mapFixedTask(fixed, TaskType.FIXED_RATE));
            }
        }

        tasks.sort(Comparator
            .comparing(ScheduledTaskInfo::type)
            .thenComparing(ScheduledTaskInfo::target));

        int cronCount = response.cron() != null ? response.cron().size() : 0;
        int fixedDelayCount = response.fixedDelay() != null ? response.fixedDelay().size() : 0;
        int fixedRateCount = response.fixedRate() != null ? response.fixedRate().size() : 0;

        return new ScheduledTasksInfo(tasks, cronCount, fixedDelayCount, fixedRateCount);
    }

    private ScheduledTaskInfo mapCronTask(ScheduledTasksResponse.CronTask cron, Locale locale) {
        return new ScheduledTaskInfo(
            extractTarget(cron.runnable()),
            TaskType.CRON,
            cron.expression(),
            cronDescriptionService.describe(cron.expression(), locale),
            null,
            parseTime(cron.lastExecution()),
            parseStatus(cron.lastExecution()),
            parseException(cron.lastExecution()),
            parseTime(cron.nextExecution())
        );
    }

    private ScheduledTaskInfo mapFixedTask(ScheduledTasksResponse.FixedTask fixed, TaskType type) {
        return new ScheduledTaskInfo(
            extractTarget(fixed.runnable()),
            type,
            formatInterval(fixed.interval()),
            null,
            fixed.interval(),
            parseTime(fixed.lastExecution()),
            parseStatus(fixed.lastExecution()),
            parseException(fixed.lastExecution()),
            parseTime(fixed.nextExecution())
        );
    }

    private String extractTarget(ScheduledTasksResponse.RunnableTarget runnable) {
        return runnable != null ? runnable.target() : "unknown";
    }

    private Instant parseTime(ScheduledTasksResponse.TaskExecution execution) {
        if (execution == null || execution.time() == null) return null;
        try {
            return Instant.parse(execution.time());
        } catch (Exception e) {
            return null;
        }
    }

    private TaskExecutionStatus parseStatus(ScheduledTasksResponse.TaskExecution execution) {
        if (execution == null) return TaskExecutionStatus.PENDING;
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

    private String formatInterval(Long intervalMs) {
        if (intervalMs == null) return "-";
        if (intervalMs < 1000) return intervalMs + "ms";
        if (intervalMs < 60000) return (intervalMs / 1000) + "s";
        if (intervalMs < 3600000) return (intervalMs / 60000) + "m";
        return (intervalMs / 3600000) + "h";
    }
}
