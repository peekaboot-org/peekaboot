package org.peekaboot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A capture that failed to restore its logger would leak into every later test in the JVM:
 * one printing through, another losing events to a detached logger.
 */
class LogCaptureTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(Emitter.class);

    @AfterEach
    void resetLogger() {
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    @Test
    void capturesWhatTheClassLogsWithoutPassingItOn() {
        try (LogCapture capture = LogCapture.attach(Emitter.class)) {
            LoggerFactory.getLogger(Emitter.class).warn("captured");

            assertThat(capture.appender().list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo("captured"));
            assertThat(logger.isAdditive()).isFalse();
        }
    }

    @Test
    void detachesAndStopsItsAppenderOnClose() {
        LogCapture capture = LogCapture.attach(Emitter.class);

        capture.close();

        assertThat(logger.isAttached(capture.appender())).isFalse();
        assertThat(capture.appender().isStarted()).isFalse();
    }

    @Test
    void restoresAdditivityOnClose() {
        LogCapture.attach(Emitter.class).close();

        assertThat(logger.isAdditive()).isTrue();
    }

    @Test
    void keepsALoggerThatWasNotAdditiveNonAdditive() {
        logger.setAdditive(false);

        LogCapture.attach(Emitter.class).close();

        assertThat(logger.isAdditive()).isFalse();
    }

    @Test
    void restoresALevelItRaised() {
        logger.setLevel(Level.WARN);

        try (LogCapture capture = LogCapture.attach(Emitter.class, Level.DEBUG)) {
            assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
        }

        assertThat(logger.getLevel()).isEqualTo(Level.WARN);
    }

    /** An unset level inherits from the parent; restoring it as anything else would pin the logger. */
    @Test
    void restoresAnUnsetLevelItRaised() {
        try (LogCapture capture = LogCapture.attach(Emitter.class, Level.DEBUG)) {
            assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
        }

        assertThat(logger.getLevel()).isNull();
    }

    private static final class Emitter {}
}
