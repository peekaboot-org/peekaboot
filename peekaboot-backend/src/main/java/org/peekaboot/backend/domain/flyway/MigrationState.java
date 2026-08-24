package org.peekaboot.backend.domain.flyway;

import java.util.Locale;

public enum MigrationState {
    SUCCESS,
    PENDING,
    FAILED,
    IGNORED,
    UNKNOWN;

    public static MigrationState fromString(String state) {
        if (state == null) return UNKNOWN;
        // Flyway MigrationState names collapsed onto the dashboard's states
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "FUTURE_SUCCESS", "MISSING_SUCCESS", "OUT_OF_ORDER", "BASELINE" -> SUCCESS;
            case "PENDING", "ABOVE_TARGET", "AVAILABLE", "OUTDATED" -> PENDING;
            case "FAILED", "FUTURE_FAILED", "MISSING_FAILED" -> FAILED;
            case "IGNORED", "BELOW_BASELINE", "SUPERSEDED", "DELETED", "UNDONE" -> IGNORED;
            default -> UNKNOWN;
        };
    }
}
