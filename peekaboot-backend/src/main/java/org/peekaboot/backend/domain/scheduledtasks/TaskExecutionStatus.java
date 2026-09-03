package org.peekaboot.backend.domain.scheduledtasks;

import java.util.Locale;

public enum TaskExecutionStatus {
    SUCCESS,
    FAILED,
    PENDING,
    RUNNING,
    UNKNOWN;

    public static TaskExecutionStatus fromString(String status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> SUCCESS;
            case "ERROR" -> FAILED;
            case "STARTED" -> RUNNING;
            default -> UNKNOWN;
        };
    }
}
