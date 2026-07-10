package net.osslabz.peekaboot.backend.domain.scheduledtasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutionStatusTest {

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "success, SUCCESS",
        "FAILED, FAILED",
        "ERROR, FAILED",
        "PENDING, PENDING",
        // Boot reports STARTED for a task that is currently executing
        "STARTED, RUNNING",
        "invalid, UNKNOWN"
    })
    void fromString_shouldParseStatus(String input, TaskExecutionStatus expected) {
        assertThat(TaskExecutionStatus.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromString_shouldHandleNull() {
        assertThat(TaskExecutionStatus.fromString(null)).isEqualTo(TaskExecutionStatus.UNKNOWN);
    }
}
