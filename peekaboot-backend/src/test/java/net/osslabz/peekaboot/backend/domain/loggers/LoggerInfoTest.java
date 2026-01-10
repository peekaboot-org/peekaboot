package net.osslabz.peekaboot.backend.domain.loggers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
