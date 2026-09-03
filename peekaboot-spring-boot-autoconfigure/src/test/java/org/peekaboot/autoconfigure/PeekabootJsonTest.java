package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTaskInfo;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTasksInfo;
import org.peekaboot.backend.domain.scheduledtasks.TaskExecutionStatus;
import org.peekaboot.backend.domain.scheduledtasks.TaskType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * Peekaboot's mapper replaces the application's Jackson bean for Peekaboot's own
 * responses, so the one thing it must not do is change the shape an unconfigured Boot
 * application already produced: byte for byte the same JSON as Boot's default mapper.
 */
class PeekabootJsonTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void writesExactlyWhatBootsDefaultMapperWrites() {
        Object sample = List.of(
                new HealthInfo(
                        HealthStatus.UP, List.of(new HealthComponent("db", HealthStatus.UP, Map.of("database", "H2")))),
                new ScheduledTasksInfo(
                        List.of(new ScheduledTaskInfo(
                                "org.example.Job.run",
                                TaskType.FIXED_RATE,
                                "PT1H",
                                "every hour",
                                3_600_000L,
                                Instant.parse("2026-09-01T10:00:00.123456789Z"),
                                TaskExecutionStatus.FAILED,
                                null,
                                Instant.parse("2026-09-01T11:00:00Z"))),
                        0,
                        0,
                        1));

        contextRunner.run(context -> {
            JsonMapper bootDefault = context.getBean(JsonMapper.class);

            assertThat(PeekabootJson.MAPPER.writeValueAsString(sample))
                    .isEqualTo(bootDefault.writeValueAsString(sample));
        });
    }
}
