package org.peekaboot.backend.domain.liquibase;

import java.util.Locale;

public enum ChangeSetExecType {
    EXECUTED,
    FAILED,
    SKIPPED,
    RERAN,
    MARK_RAN,
    UNKNOWN;

    public static ChangeSetExecType fromString(String execType) {
        if (execType == null) {
            return UNKNOWN;
        }
        return switch (execType.toUpperCase(Locale.ROOT)) {
            case "EXECUTED" -> EXECUTED;
            case "FAILED" -> FAILED;
            case "SKIPPED" -> SKIPPED;
            case "RERAN" -> RERAN;
            case "MARK_RAN" -> MARK_RAN;
            default -> UNKNOWN;
        };
    }
}
