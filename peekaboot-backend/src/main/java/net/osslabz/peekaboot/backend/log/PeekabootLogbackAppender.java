package net.osslabz.peekaboot.backend.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import net.osslabz.peekaboot.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.tracing.event.TraceEventBus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class PeekabootLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";

    private final TraceLogStore logStore;
    private Level minLevel = Level.DEBUG;
    private TraceEventBus eventBus;

    public PeekabootLogbackAppender(TraceLogStore logStore) {
        this.logStore = logStore;
        setName("peekaboot");
    }

    public void setMinLevel(Level minLevel) {
        this.minLevel = minLevel;
    }

    public void setEventBus(TraceEventBus eventBus) {
        this.eventBus = eventBus;
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

        String spanId = mdc != null ? mdc.get(SPAN_ID_KEY) : null;
        Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());
        String level = event.getLevel().toString();
        String loggerName = event.getLoggerName();
        String message = event.getFormattedMessage();
        String threadName = event.getThreadName();

        LogEntry entry = new LogEntry(
                traceId,
                spanId,
                timestamp,
                level,
                loggerName,
                message,
                threadName,
                mdc != null ? new HashMap<>(mdc) : Map.of()
        );

        logStore.addLog(entry);

        if (eventBus != null) {
            eventBus.publish(new LogCapturedEvent(
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
