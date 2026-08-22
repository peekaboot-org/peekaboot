package org.peekaboot.backend.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;

/**
 * Logback appender that captures log events and publishes them via Spring events.
 * Uses MDC for traceId/spanId correlation since logback events contain frozen MDC state.
 *
 * <p>Every event Logback delivers is captured. The appender applies no threshold of its own:
 * it hangs off the root logger, so the levels configured under {@code logging.level.*} have
 * already decided what arrives, and a second hidden floor here only made the trace's log tab
 * disagree with the log file. Per-trace volume stays bounded by
 * {@code peekaboot.tracing.max-logs-per-trace}.
 */
public class PeekabootLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";

    private ApplicationEventPublisher eventPublisher;

    public PeekabootLogbackAppender() {
        setName("peekaboot");
    }

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }

        Map<String, String> mdc = event.getMDCPropertyMap();
        String traceId = mdc != null ? mdc.get(TRACE_ID_KEY) : null;

        if (traceId == null || traceId.isBlank()) {
            return;
        }

        if (eventPublisher != null) {
            String spanId = mdc.get(SPAN_ID_KEY);
            Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());
            String level = event.getLevel().toString();
            String loggerName = event.getLoggerName();
            String message = event.getFormattedMessage();
            String threadName = event.getThreadName();

            eventPublisher.publishEvent(new LogCapturedEvent(
                    traceId,
                    spanId,
                    timestamp,
                    level,
                    loggerName,
                    message,
                    threadName
            ));
        }
    }
}
