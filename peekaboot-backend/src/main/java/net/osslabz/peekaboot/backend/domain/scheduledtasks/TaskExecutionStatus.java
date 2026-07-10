package net.osslabz.peekaboot.backend.domain.scheduledtasks;

public enum TaskExecutionStatus {
    SUCCESS,
    FAILED,
    PENDING,
    RUNNING,
    UNKNOWN;

    public static TaskExecutionStatus fromString(String status) {
        if (status == null) return UNKNOWN;
        return switch (status.toUpperCase()) {
            case "SUCCESS" -> SUCCESS;
            case "FAILED", "ERROR" -> FAILED;
            case "PENDING" -> PENDING;
            case "STARTED" -> RUNNING;
            default -> UNKNOWN;
        };
    }
}
