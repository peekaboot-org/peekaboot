package org.peekaboot.backend.tracing.capture;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.stacktrace.CapturedStackTraces;
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
 * {@code peekaboot.tracing.max-logs-per-trace}. A line written outside any trace is captured too,
 * without a trace id, and the store keeps the newest {@code peekaboot.tracing.max-untraced-logs} of
 * them.
 */
public class PeekabootLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";

    private final ApplicationEventPublisher eventPublisher;

    public PeekabootLogbackAppender(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        setName("peekaboot");
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = Objects.requireNonNullElse(event.getMDCPropertyMap(), Map.of());
        String traceId = present(mdc.get(TRACE_ID_KEY));
        String spanId = traceId != null ? mdc.get(SPAN_ID_KEY) : null;
        Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());
        String level = event.getLevel().toString();
        String message = event.getFormattedMessage();
        IThrowableProxy throwable = event.getThrowableProxy();
        String stackTrace =
                throwable == null ? null : CapturedStackTraces.capLines(ThrowableProxyUtil.asString(throwable));

        eventPublisher.publishEvent(new LogCapturedEvent(
                traceId, spanId, timestamp, level, event.getLoggerName(), event.getThreadName(), message, stackTrace));
    }

    /** A blank trace id names no trace: the line is kept as an untraced one. */
    private static @Nullable String present(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
