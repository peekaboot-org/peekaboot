package org.peekaboot.backend.tracing.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LogCapturedEventTest {

    @Test
    void isErrorAndIsWarnReadTheLevelCaseInsensitively() {
        assertThat(logAt("ERROR").isError()).isTrue();
        assertThat(logAt("error").isError()).isTrue();
        assertThat(logAt("WARN").isWarn()).isTrue();
        assertThat(logAt("warn").isWarn()).isTrue();
    }

    @Test
    void otherLevelsAreNeitherErrorNorWarn() {
        assertThat(logAt("INFO").isError()).isFalse();
        assertThat(logAt("INFO").isWarn()).isFalse();
        assertThat(logAt("WARN").isError()).isFalse();
        assertThat(logAt("ERROR").isWarn()).isFalse();
        assertThat(logAt(null).isError()).isFalse();
    }

    private static LogCapturedEvent logAt(String level) {
        return new LogCapturedEvent("trace1", "span1", Instant.EPOCH, level, "Logger", "message", "main");
    }
}
