package net.osslabz.peekaboot.tracing.event;

import java.time.Instant;

public record LogCapturedEvent(
        String traceId,
        String spanId,
        Instant timestamp,
        String level,
        String loggerName,
        String message,
        String threadName
) implements TraceDataEvent {
}
