package org.peekaboot.backend.domain.liquibase;

import org.peekaboot.backend.text.EnumNames;

public enum ChangeSetExecType {
    EXECUTED,
    FAILED,
    SKIPPED,
    RERAN,
    MARK_RAN,
    UNKNOWN;

    public static ChangeSetExecType fromString(String execType) {
        return EnumNames.byName(ChangeSetExecType.class, execType, UNKNOWN);
    }
}
