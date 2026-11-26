package org.peekaboot.backend.tracing.capture;

/**
 * The MDC keys the application's tracing writes the current trace and span id under, which
 * the log appender reads to link a line to its trace.
 */
public record MdcKeys(String traceId, String spanId) {

    public static final MdcKeys DEFAULTS = new MdcKeys("traceId", "spanId");
}
