package org.peekaboot.backend.domain.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MigrationStateTest {

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "success, SUCCESS",
        "PENDING, PENDING",
        "FAILED, FAILED",
        "IGNORED, IGNORED",
        "invalid, UNKNOWN",
        // real Flyway states beyond the basic four
        "FUTURE_SUCCESS, SUCCESS",
        "MISSING_SUCCESS, SUCCESS",
        "OUT_OF_ORDER, SUCCESS",
        "BASELINE, SUCCESS",
        "FUTURE_FAILED, FAILED",
        "MISSING_FAILED, FAILED",
        "ABOVE_TARGET, PENDING",
        "AVAILABLE, PENDING",
        "OUTDATED, PENDING",
        "BELOW_BASELINE, IGNORED",
        "SUPERSEDED, IGNORED",
        "DELETED, IGNORED",
        "UNDONE, IGNORED"
    })
    void fromString_shouldParseState(String input, MigrationState expected) {
        assertThat(MigrationState.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromString_shouldHandleNull() {
        assertThat(MigrationState.fromString(null)).isEqualTo(MigrationState.UNKNOWN);
    }
}
