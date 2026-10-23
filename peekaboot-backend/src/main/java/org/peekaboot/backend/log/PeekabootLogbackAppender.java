package org.peekaboot.backend.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.text.CountFormat;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Logback appender that captures log events and publishes them via Spring events.
 * Uses MDC for traceId/spanId correlation since logback events contain frozen MDC state.
 *
 * <p>Every event Logback delivers is captured. The appender applies no threshold of its own:
 * it hangs off the root logger, so the levels configured under {@code logging.level.*} have
 * already decided what arrives, and a second floor here would only make the Logs tab disagree
 * with the log file. Per-trace volume stays bounded by
 * {@code peekaboot.tracing.max-logs-per-trace}.
 */
public class PeekabootLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";

    /**
     * A StackOverflowError's own trace runs to 1024 frames by default, and a developer hits
     * one routinely - unlike a count-based cap sized for an ordinary 30-80 line trace, this
     * bounds a single record before it can dominate the trace store's retention.
     */
    private static final int MAX_CAPTURED_LINES = 1000;

    private final ApplicationEventPublisher eventPublisher;

    public PeekabootLogbackAppender(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        setName("peekaboot");
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String traceId = mdc != null ? mdc.get(TRACE_ID_KEY) : null;

        if (traceId == null || traceId.isBlank()) {
            return;
        }

        String spanId = mdc.get(SPAN_ID_KEY);
        Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());
        String level = event.getLevel().toString();
        String message = event.getFormattedMessage();
        IThrowableProxy throwable = event.getThrowableProxy();
        String stackTrace = throwable == null ? null : capLines(ThrowableProxyUtil.asString(throwable));

        eventPublisher.publishEvent(new LogCapturedEvent(traceId, spanId, timestamp, level, message, stackTrace));
    }

    private static String capLines(String trace) {
        List<String> lines = trace.lines().toList();
        if (lines.size() <= MAX_CAPTURED_LINES) {
            return trace;
        }
        int omitted = lines.size() - MAX_CAPTURED_LINES;
        return String.join("\n", lines.subList(0, MAX_CAPTURED_LINES)) + "\n... " + CountFormat.of(omitted, "line")
                + " omitted";
    }
}
