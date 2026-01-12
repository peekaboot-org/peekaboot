package net.osslabz.peekaboot.backend.log;

import java.time.Instant;
import java.util.Map;

public record LogEntry(
        String traceId,
        String spanId,
        Instant timestamp,
        String level,
        String loggerName,
        String message,
        String threadName,
        Map<String, String> mdc
) {
    public String loggerShortName() {
        if (loggerName == null) return "";
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot >= 0 ? loggerName.substring(lastDot + 1) : loggerName;
    }
}
