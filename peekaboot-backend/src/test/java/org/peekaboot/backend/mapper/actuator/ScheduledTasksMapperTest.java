package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.ScheduledTasksResponse;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTasksInfo;
import org.peekaboot.backend.domain.scheduledtasks.TaskExecutionStatus;
import org.peekaboot.backend.domain.scheduledtasks.TaskType;
import org.peekaboot.backend.service.CronDescriptionService;

class ScheduledTasksMapperTest {

    private final ScheduledTasksMapper mapper = new ScheduledTasksMapper(new CronDescriptionService());

    @Test
    void map_shouldExtractCronTasks() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(new ScheduledTasksResponse.CronTask(
                        "0 0 * * * *",
                        null,
                        new ScheduledTasksResponse.TaskExecution(null, null, "2026-01-11T07:00:00Z"),
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.cronTask"))),
                List.of(),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.cronCount()).isEqualTo(1);
        assertThat(result.tasks().get(0).type()).isEqualTo(TaskType.CRON);
        assertThat(result.tasks().get(0).schedule()).isEqualTo("0 0 * * * *");
        assertThat(result.tasks().get(0).target()).isEqualTo("com.example.Scheduler.cronTask");
    }

    @Test
    void map_shouldExtractFixedDelayTasks() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        5000L,
                        new ScheduledTasksResponse.TaskExecution(null, "SUCCESS", "2026-01-11T06:49:25Z"),
                        new ScheduledTasksResponse.TaskExecution(null, null, "2026-01-11T06:49:30Z"),
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.fixedDelay"))),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.fixedDelayCount()).isEqualTo(1);
        assertThat(result.tasks().get(0).type()).isEqualTo(TaskType.FIXED_DELAY);
        assertThat(result.tasks().get(0).schedule()).isEqualTo("5s");
        assertThat(result.tasks().get(0).intervalMs()).isEqualTo(5000L);
        assertThat(result.tasks().get(0).lastStatus()).isEqualTo(TaskExecutionStatus.SUCCESS);
    }

    @Test
    void map_shouldExtractFixedRateTasks() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        3600000L,
                        new ScheduledTasksResponse.TaskExecution(null, "SUCCESS", "2026-01-11T06:49:20Z"),
                        new ScheduledTasksResponse.TaskExecution(null, null, "2026-01-11T07:49:20Z"),
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.fixedRate"))));

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.fixedRateCount()).isEqualTo(1);
        assertThat(result.tasks().get(0).type()).isEqualTo(TaskType.FIXED_RATE);
        assertThat(result.tasks().get(0).schedule()).isEqualTo("1h");
    }

    @Test
    void map_shouldHandleNullInput() {
        ScheduledTasksInfo result = mapper.map(null, Locale.ENGLISH);
        assertThat(result.tasks()).isEmpty();
        assertThat(result.cronCount()).isZero();
    }

    @Test
    void map_shouldHandleFailedStatus() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        1000L,
                        new ScheduledTasksResponse.TaskExecution(
                                new ScheduledTasksResponse.TaskExceptionInfo(
                                        "Task failed", "java.lang.NullPointerException"),
                                "ERROR",
                                "2026-01-11T06:49:20Z"),
                        null,
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.failingTask"))),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks().get(0).lastStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(result.tasks().get(0).lastException()).isEqualTo("java.lang.NullPointerException: Task failed");
    }

    @Test
    void map_shouldSortByTypeAndTarget() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(
                        createCronTask("0 0 * * * *", "b.Scheduler.cronB"),
                        createCronTask("0 0 * * * *", "a.Scheduler.cronA")),
                List.of(createFixedTask(1000L, "c.Scheduler.fixed")),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(3);
        assertThat(result.tasks().get(0).target()).isEqualTo("a.Scheduler.cronA");
        assertThat(result.tasks().get(1).target()).isEqualTo("b.Scheduler.cronB");
        assertThat(result.tasks().get(2).target()).isEqualTo("c.Scheduler.fixed");
    }

    @Test
    void map_shouldFormatIntervalsCorrectly() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(createFixedTask(500L, "a"), createFixedTask(30000L, "b"), createFixedTask(120000L, "c")),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks().get(0).schedule()).isEqualTo("500ms");
        assertThat(result.tasks().get(1).schedule()).isEqualTo("30s");
        assertThat(result.tasks().get(2).schedule()).isEqualTo("2m");
    }

    @Test
    void map_cronTask_shouldPopulateScheduleDescription() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(createCronTask("0 0 * * * *", "com.example.Scheduler.hourlyTask")), List.of(), List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).scheduleDescription()).isNotNull();
        assertThat(result.tasks().get(0).scheduleDescription().toLowerCase(Locale.ROOT))
                .contains("hour");
    }

    @Test
    void map_fixedDelayTask_shouldHaveNullScheduleDescription() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(), List.of(createFixedTask(5000L, "com.example.Scheduler.fixedDelayTask")), List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).scheduleDescription()).isNull();
    }

    @Test
    void map_fixedRateTask_shouldHaveNullScheduleDescription() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        10000L,
                        null,
                        null,
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.fixedRateTask"))));

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).scheduleDescription()).isNull();
    }

    @Test
    void map_shouldParseLastAndNextExecutionTimesForCronTask() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(new ScheduledTasksResponse.CronTask(
                        "0 0 * * * *",
                        new ScheduledTasksResponse.TaskExecution(null, "SUCCESS", "2026-01-11T06:00:00Z"),
                        new ScheduledTasksResponse.TaskExecution(null, null, "2026-01-11T07:00:00Z"),
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.cronTask"))),
                List.of(),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks().get(0).lastExecution()).isEqualTo(Instant.parse("2026-01-11T06:00:00Z"));
        assertThat(result.tasks().get(0).nextExecution()).isEqualTo(Instant.parse("2026-01-11T07:00:00Z"));
    }

    @Test
    void map_shouldParseLastAndNextExecutionTimesForFixedTask() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        5000L,
                        new ScheduledTasksResponse.TaskExecution(null, "SUCCESS", "2026-01-11T06:49:25Z"),
                        new ScheduledTasksResponse.TaskExecution(null, null, "2026-01-11T06:49:30Z"),
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.fixedDelay"))),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks().get(0).lastExecution()).isEqualTo(Instant.parse("2026-01-11T06:49:25Z"));
        assertThat(result.tasks().get(0).nextExecution()).isEqualTo(Instant.parse("2026-01-11T06:49:30Z"));
    }

    @Test
    void map_shouldReturnNullExecutionTimeForMalformedTimestamp() {
        ScheduledTasksResponse response = new ScheduledTasksResponse(
                List.of(),
                List.of(new ScheduledTasksResponse.FixedTask(
                        5000L,
                        new ScheduledTasksResponse.TaskExecution(null, "SUCCESS", "not-a-timestamp"),
                        null,
                        new ScheduledTasksResponse.RunnableTarget("com.example.Scheduler.fixedDelay"))),
                List.of());

        ScheduledTasksInfo result = mapper.map(response, Locale.ENGLISH);

        assertThat(result.tasks().get(0).lastExecution()).isNull();
    }

    private ScheduledTasksResponse.CronTask createCronTask(String expr, String target) {
        return new ScheduledTasksResponse.CronTask(expr, null, null, new ScheduledTasksResponse.RunnableTarget(target));
    }

    private ScheduledTasksResponse.FixedTask createFixedTask(long interval, String target) {
        return new ScheduledTasksResponse.FixedTask(
                interval, null, null, new ScheduledTasksResponse.RunnableTarget(target));
    }
}
