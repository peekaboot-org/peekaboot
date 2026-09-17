package org.peekaboot.backend.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Drives the appender through a real Logback logger rather than a stubbed
 * {@code ILoggingEvent}, so the MDC snapshot and level resolution are the real ones.
 */
class PeekabootLogbackAppenderTest {

    private static final String TRACE_ID = "cafebabecafebabecafebabecafebabe";
    private static final String SPAN_ID = "deadbeefdeadbeef";

    private LoggerContext loggerContext;
    private Logger logger;
    private PeekabootLogbackAppender appender;
    private List<LogCapturedEvent> captured;

    @BeforeEach
    void setUp() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        captured = new ArrayList<>();

        appender = new PeekabootLogbackAppender();
        appender.setEventPublisher(event -> {
            if (event instanceof LogCapturedEvent logEvent) {
                captured.add(logEvent);
            }
        });
        appender.setContext(loggerContext);
        appender.start();

        logger = loggerContext.getLogger(PeekabootLogbackAppenderTest.class);
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
        logger.setAdditive(true);
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("captures a TRACE event - the appender must not impose a floor above what Logback delivers")
    void capturesTraceLevelEventWhenCorrelated() {
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);

        logger.trace("a trace line inside a request");

        assertThat(captured)
                .as("a TRACE event carrying a traceId reaches the appender, so it must be captured; "
                        + "Logback's own logger levels already decide what is delivered")
                .hasSize(1);
        assertThat(captured.getFirst().level()).isEqualTo("TRACE");
        assertThat(captured.getFirst().traceId()).isEqualTo(TRACE_ID);
        assertThat(captured.getFirst().spanId()).isEqualTo(SPAN_ID);
        assertThat(captured.getFirst().message()).isEqualTo("a trace line inside a request");
    }

    @Test
    @DisplayName("captures DEBUG, INFO, WARN and ERROR alike")
    void capturesEveryLevelDeliveredByLogback() {
        MDC.put("traceId", TRACE_ID);

        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");

        assertThat(captured).extracting(LogCapturedEvent::level).containsExactly("DEBUG", "INFO", "WARN", "ERROR");
        assertThat(captured).extracting(LogCapturedEvent::spanId).containsOnlyNulls();
    }

    @Test
    @DisplayName("drops an uncorrelated event - it belongs to no trace")
    void ignoresEventWithoutTraceId() {
        logger.error("no MDC at all");

        MDC.put("traceId", "   ");
        logger.error("blank traceId");

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("respects the level Logback resolves - a level the logger filters never arrives")
    void doesNotSeeEventsFilteredByTheLoggerLevel() {
        logger.setLevel(Level.INFO);
        MDC.put("traceId", TRACE_ID);

        logger.debug("below the logger's own threshold");
        logger.info("at the threshold");

        assertThat(captured).extracting(LogCapturedEvent::level).containsExactly("INFO");
    }

    @Test
    @DisplayName("a stopped appender captures nothing")
    void ignoresEventsWhileStopped() {
        appender.stop();
        MDC.put("traceId", TRACE_ID);

        logger.error("while stopped");

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("carries the stack trace of a logged throwable")
    void carriesTheStackTraceOfALoggedThrowable() {
        MDC.put("traceId", TRACE_ID);

        logger.error("gateway call failed", new IllegalStateException("gateway unreachable"));

        assertThat(captured.getFirst().stackTrace())
                .contains("java.lang.IllegalStateException: gateway unreachable")
                .contains("\tat ");
    }

    @Test
    @DisplayName("carries no trace for a log without a throwable")
    void carriesNoTraceForALogWithoutAThrowable() {
        MDC.put("traceId", TRACE_ID);

        logger.error("no throwable here");

        assertThat(captured.getFirst().stackTrace()).isNull();
    }

    @Test
    @DisplayName("passes an ordinary trace through untouched")
    void anOrdinaryTraceIsNotTruncated() {
        MDC.put("traceId", TRACE_ID);

        logger.error("gateway call failed", new IllegalStateException("gateway unreachable"));

        String stackTrace = captured.getFirst().stackTrace();
        assertThat(stackTrace.lines().count()).isLessThan(1000);
        assertThat(stackTrace).doesNotContain("omitted");
    }

    /**
     * A StackOverflowError's own trace routinely runs to 1024 frames; this stands in for one
     * without actually triggering a real overflow.
     */
    @Test
    @DisplayName("caps an over-long trace at 1000 lines and says how many were dropped")
    void capsAnOverLongTraceAndMarksWhatWasDropped() {
        MDC.put("traceId", TRACE_ID);
        IllegalStateException deep = new IllegalStateException("too deep");
        StackTraceElement[] frames = new StackTraceElement[1500];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("com.example.Recursor", "recurse", "Recursor.java", i);
        }
        deep.setStackTrace(frames);

        logger.error("recursion blew the stack", deep);

        // one header line + 1500 frame lines = 1501, capped to 1000 kept lines plus a marker
        List<String> lines = captured.getFirst().stackTrace().lines().toList();
        assertThat(lines).hasSize(1001);
        assertThat(lines.getFirst()).contains("IllegalStateException: too deep");
        assertThat(lines.get(999)).startsWith("\tat com.example.Recursor.recurse");
        assertThat(lines.getLast()).contains("501").contains("omitted");
    }
}
