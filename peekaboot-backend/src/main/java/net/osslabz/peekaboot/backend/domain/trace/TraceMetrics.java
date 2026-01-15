package net.osslabz.peekaboot.backend.domain.trace;

public record TraceMetrics(
    int totalSpans,
    int dbQueryCount,
    long dbTotalDurationMs,
    int httpCallCount,
    long httpTotalDurationMs,
    int errorCount
) {}
