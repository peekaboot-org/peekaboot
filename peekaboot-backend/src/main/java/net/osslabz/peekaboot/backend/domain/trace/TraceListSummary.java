package net.osslabz.peekaboot.backend.domain.trace;

/**
 * Summary statistics aggregated across multiple traces.
 * Used at the response level for trace list endpoints.
 */
public record TraceListSummary(
    int traceCount,
    int errorCount,
    int slowCount,
    double avgDurationMs
) {}
