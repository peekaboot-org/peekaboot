package org.peekaboot.backend.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;

/**
 * Logback appender that captures log events and publishes them via Spring events.
 * Uses MDC for traceId/spanId correlation since logback events contain frozen MDC state.
 */
public class PeekabootLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";

    private Level minLevel = Level.DEBUG;
    private ApplicationEventPublisher eventPublisher;

    public PeekabootLogbackAppender() {
        setName("peekaboot");
    }

    public void setMinLevel(Level minLevel) {
        this.minLevel = minLevel;
    }

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }

        if (!event.getLevel().isGreaterOrEqual(minLevel)) {
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
