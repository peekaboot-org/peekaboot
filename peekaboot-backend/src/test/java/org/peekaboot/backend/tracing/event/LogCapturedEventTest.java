package org.peekaboot.backend.tracing.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;

import org.junit.jupiter.api.Test;

class LogCapturedEventTest {

    @Test
    void isErrorAndIsWarnReadTheLevelCaseInsensitively() {
        assertThat(log("trace1").at("ERROR").build().isError()).isTrue();
        assertThat(log("trace1").at("error").build().isError()).isTrue();
        assertThat(log("trace1").at("WARN").build().isWarn()).isTrue();
        assertThat(log("trace1").at("warn").build().isWarn()).isTrue();
    }

    @Test
    void otherLevelsAreNeitherErrorNorWarn() {
        assertThat(log("trace1").at("INFO").build().isError()).isFalse();
        assertThat(log("trace1").at("INFO").build().isWarn()).isFalse();
        assertThat(log("trace1").at("WARN").build().isError()).isFalse();
        assertThat(log("trace1").at("ERROR").build().isWarn()).isFalse();
        assertThat(log("trace1").at(null).build().isError()).isFalse();
    }
}
