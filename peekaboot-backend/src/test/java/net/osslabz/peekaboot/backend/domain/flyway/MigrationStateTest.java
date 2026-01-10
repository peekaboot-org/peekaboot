package net.osslabz.peekaboot.backend.domain.flyway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationStateTest {

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "success, SUCCESS",
        "PENDING, PENDING",
        "FAILED, FAILED",
        "IGNORED, IGNORED",
        "invalid, UNKNOWN"
    })
    void fromString_shouldParseState(String input, MigrationState expected) {
        assertThat(MigrationState.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromString_shouldHandleNull() {
        assertThat(MigrationState.fromString(null)).isEqualTo(MigrationState.UNKNOWN);
    }
}
