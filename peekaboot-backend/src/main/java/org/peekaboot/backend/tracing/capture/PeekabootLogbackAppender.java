package org.peekaboot.backend.tracing.capture;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.peekaboot.backend.stacktrace.CapturedStackTraces;
import org.peekaboot.backend.tracing.event.CapturedText;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Logback appender that captures log events and publishes each as a {@link LogCapturedEvent}.
 * Uses MDC, under the {@link MdcKeys} it is given, for traceId/spanId correlation since logback events contain frozen MDC state.
 *
 * <p>Every event Logback delivers is captured, except what {@link SecurityPostureListener} logs:
 * its banner prints the generated dashboard password, which belongs in the application's log and
 * in nothing Peekaboot stores or serves. The appender applies no threshold of its own:
 * it hangs off the root logger, so the levels configured under {@code logging.level.*} have
 * already decided what arrives, and a second floor here would only make the Logs tab disagree
 * with the log file. Per-trace volume stays bounded by
 * {@code peekaboot.tracing.max-logs-per-trace}, and a line's message by
 * {@link CapturedText#MAX_LENGTH}. A line written outside any trace is captured too,
 * without a trace id, and the store keeps the newest {@code peekaboot.tracing.max-untraced-logs} of
 * them.
 */
public class PeekabootLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private final ApplicationEventPublisher eventPublisher;
    private final MdcKeys mdcKeys;

    public PeekabootLogbackAppender(ApplicationEventPublisher eventPublisher, MdcKeys mdcKeys) {
        this.eventPublisher = eventPublisher;
        this.mdcKeys = mdcKeys;
        setName("peekaboot");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (SecurityPostureListener.class.getName().equals(event.getLoggerName())) {
            return;
        }
        Map<String, String> mdc = Objects.requireNonNullElse(event.getMDCPropertyMap(), Map.of());
        String traceId = present(mdc.get(mdcKeys.traceId()));
        String spanId = traceId != null ? mdc.get(mdcKeys.spanId()) : null;
        Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());
        String level = event.getLevel().toString();
        // Logback formats a null pattern as a null message; every captured line carries one.
        String message = CapturedText.cap(Objects.requireNonNullElse(event.getFormattedMessage(), ""));
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
