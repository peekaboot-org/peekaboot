package net.osslabz.peekaboot.backend.domain.flyway;

public enum MigrationState {
    SUCCESS,
    PENDING,
    FAILED,
    IGNORED,
    UNKNOWN;

    public static MigrationState fromString(String state) {
        if (state == null) return UNKNOWN;
        return switch (state.toUpperCase()) {
            case "SUCCESS" -> SUCCESS;
            case "PENDING" -> PENDING;
            case "FAILED" -> FAILED;
            case "IGNORED" -> IGNORED;
            default -> UNKNOWN;
        };
    }
}
