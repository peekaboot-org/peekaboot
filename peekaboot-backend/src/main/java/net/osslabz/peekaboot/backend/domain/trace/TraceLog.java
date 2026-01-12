package net.osslabz.peekaboot.backend.domain.trace;

import java.time.Instant;

public record TraceLog(
        String spanId,
        Instant timestamp,
        String level,
        String loggerName,
        String message,
        String threadName
) {
}
