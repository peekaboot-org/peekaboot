package net.osslabz.peekaboot.backend.domain.trace;

public record TraceSummary(
    int totalTraces,
    int errorCount,
    int slowCount,
    double avgDurationMs
) {}
