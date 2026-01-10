package net.osslabz.peekaboot.backend.domain.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthInfoTest {

    @ParameterizedTest
    @CsvSource({
        "UP, UP",
        "up, UP",
        "DOWN, DOWN",
        "down, DOWN",
        "OUT_OF_SERVICE, OUT_OF_SERVICE",
        "UNKNOWN, UNKNOWN",
        "invalid, UNKNOWN",
        "'', UNKNOWN"
    })
    void fromString_shouldParseStatus(String input, HealthStatus expected) {
        assertThat(HealthStatus.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromString_shouldHandleNull() {
        assertThat(HealthStatus.fromString(null)).isEqualTo(HealthStatus.UNKNOWN);
    }
}
