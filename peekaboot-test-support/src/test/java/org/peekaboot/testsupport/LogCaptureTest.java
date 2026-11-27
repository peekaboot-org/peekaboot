package org.peekaboot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    void eventsAreASnapshotOfWhatWasCapturedSoFar() {
        try (LogCapture capture = LogCapture.attach(Emitter.class)) {
            LoggerFactory.getLogger(Emitter.class).warn("first");

            List<ILoggingEvent> events = capture.events();
            LoggerFactory.getLogger(Emitter.class).warn("second");

            assertThat(events).extracting(ILoggingEvent::getFormattedMessage).containsExactly("first");
            assertThat(capture.events())
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("first", "second");
        }
    }

    /** Logback appends under the appender's monitor, so a snapshot taken under it never sees an append half done. */
    @Test
    void eventsWaitForAnAppendInProgress() throws InterruptedException {
        try (LogCapture capture = LogCapture.attach(Emitter.class)) {
            CountDownLatch appending = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread appender = Thread.ofPlatform().start(() -> {
                synchronized (capture.appender()) {
                    appending.countDown();
                    awaitQuietly(release);
                }
            });
            AtomicReference<List<ILoggingEvent>> snapshot = new AtomicReference<>();
            Thread reader;
            try {
                assertThat(appending.await(5, TimeUnit.SECONDS)).isTrue();
                reader = Thread.ofPlatform().start(() -> snapshot.set(capture.events()));

                await().atMost(Duration.ofSeconds(5)).until(() -> reader.getState() == Thread.State.BLOCKED);
            } finally {
                release.countDown();
            }

            assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
            assertThat(appender.join(Duration.ofSeconds(5))).isTrue();
            assertThat(snapshot.get()).isEmpty();
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

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Emitter {}
}
