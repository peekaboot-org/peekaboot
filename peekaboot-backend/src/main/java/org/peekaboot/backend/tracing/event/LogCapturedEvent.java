package org.peekaboot.backend.tracing.event;

import java.time.Instant;

/**
 * One log line captured during a traced request.
 *
 * @param stackTrace {@code ThrowableProxyUtil.asString}'s rendering of the logged throwable, or
 *     null where there was none. Close to but not identical to {@code Throwable.printStackTrace}'s
 *     own output: an elided run reads "... N common frames omitted" rather than "... N more",
 *     and enabling Logback's packaging data would append " [jar:version]" to every frame -
 *     inside the substring an exclusion pattern matches against. Capped at
 *     {@code PeekabootLogbackAppender}'s line limit, with a trailing marker where it was.
 */
public record LogCapturedEvent(
        String traceId,
        String spanId,
        Instant timestamp,
        String level,
        String loggerName,
        String message,
        String threadName,
        String stackTrace) {

    public boolean isError() {
        return "ERROR".equalsIgnoreCase(level);
    }

    public boolean isWarn() {
        return "WARN".equalsIgnoreCase(level);
    }
}
