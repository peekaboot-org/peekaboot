package org.peekaboot.backend.domain.loggers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoggerInfoTest {

    @Test
    void isConfigured_shouldReturnTrueWhenConfiguredLevelSet() {
        LoggerInfo info = new LoggerInfo("com.example", "DEBUG", "DEBUG");
        assertThat(info.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_shouldReturnFalseWhenConfiguredLevelNull() {
        LoggerInfo info = new LoggerInfo("com.example", null, "INFO");
        assertThat(info.isConfigured()).isFalse();
    }
}
