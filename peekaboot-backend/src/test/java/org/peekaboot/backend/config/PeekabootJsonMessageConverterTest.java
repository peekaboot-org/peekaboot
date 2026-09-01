package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTaskInfo;
import org.peekaboot.backend.domain.scheduledtasks.TaskExecutionStatus;
import org.peekaboot.backend.domain.scheduledtasks.TaskType;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.MockHttpOutputMessage;

class PeekabootJsonMessageConverterTest {

    private final PeekabootJsonMessageConverter converter = new PeekabootJsonMessageConverter();

    @Test
    void writesOnlyPeekabootsOwnTypes() {
        assertThat(converter.canWrite(ScheduledTaskInfo.class, MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat(converter.canWrite(
                        ResolvableType.forClass(ScheduledTaskInfo.class),
                        ScheduledTaskInfo.class,
                        MediaType.APPLICATION_JSON))
                .isTrue();

        // the host's own response types keep the host's converters
        assertThat(converter.canWrite(ProblemDetail.class, MediaType.APPLICATION_JSON))
                .isFalse();
        assertThat(converter.canWrite(Map.class, MediaType.APPLICATION_JSON)).isFalse();
    }

    @Test
    void neverReads() {
        assertThat(converter.canRead(ScheduledTaskInfo.class, MediaType.APPLICATION_JSON))
                .isFalse();
        assertThat(converter.canRead(ResolvableType.forClass(ScheduledTaskInfo.class), MediaType.APPLICATION_JSON))
                .isFalse();
    }

    /** The wire shape the dashboard reads: camelCase, nulls present, Instants as ISO-8601 strings. */
    @Test
    void writesCamelCaseNullsAndIsoInstants() throws Exception {
        ScheduledTaskInfo task = new ScheduledTaskInfo(
                "org.example.Job.run",
                TaskType.CRON,
                "0 0 * * * *",
                "every hour",
                null,
                Instant.parse("2026-09-01T10:00:00.123456Z"),
                TaskExecutionStatus.SUCCESS,
                null,
                Instant.parse("2026-09-01T11:00:00Z"));
        MockHttpOutputMessage message = new MockHttpOutputMessage();

        converter.write(task, MediaType.APPLICATION_JSON, message);

        assertThat(message.getBodyAsString())
                .isEqualTo("{\"target\":\"org.example.Job.run\",\"type\":\"CRON\",\"schedule\":\"0 0 * * * *\","
                        + "\"scheduleDescription\":\"every hour\",\"intervalMs\":null,"
                        + "\"lastExecution\":\"2026-09-01T10:00:00.123456Z\",\"lastStatus\":\"SUCCESS\","
                        + "\"lastException\":null,\"nextExecution\":\"2026-09-01T11:00:00Z\"}");
    }
}
