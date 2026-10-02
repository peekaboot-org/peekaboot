package org.peekaboot.backend.domain.liquibase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChangeSetExecTypeTest {

    @ParameterizedTest
    @CsvSource({
        "EXECUTED, EXECUTED",
        "executed, EXECUTED",
        "FAILED, FAILED",
        "SKIPPED, SKIPPED",
        "RERAN, RERAN",
        "MARK_RAN, MARK_RAN",
        "invalid, UNKNOWN",
        "'', UNKNOWN"
    })
    void fromStringReadsEachLiquibaseExecType(String input, ChangeSetExecType expected) {
        assertThat(ChangeSetExecType.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromStringReadsNullAsUnknown() {
        assertThat(ChangeSetExecType.fromString(null)).isEqualTo(ChangeSetExecType.UNKNOWN);
    }
}
