package net.osslabz.peekaboot.backend.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class PeekabootLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final String TRACE_ID_KEY = "traceId";

    private final TraceLogStore logStore;
    private Level minLevel = Level.DEBUG;

    public PeekabootLogbackAppender(TraceLogStore logStore) {
        this.logStore = logStore;
        setName("peekaboot");
    }

    public void setMinLevel(Level minLevel) {
        this.minLevel = minLevel;
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

        LogEntry entry = new LogEntry(
                traceId,
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                event.getThreadName(),
                mdc != null ? new HashMap<>(mdc) : Map.of()
        );

        logStore.addLog(entry);
    }
}
