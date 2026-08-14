package org.peekaboot.backend.domain.trace;

/**
 * Summary statistics for raw trace responses.
 */
public record TraceRawSummary(
    int traceCount,
    CountDuration spans,
    CountDuration queries,
    Count logs,
    Count errors
) {
    public record CountDuration(int count, long totalDurationMs) {}
    public record Count(int count) {}

    public static TraceRawSummary empty() {
        return new TraceRawSummary(
            0,
            new CountDuration(0, 0),
            new CountDuration(0, 0),
            new Count(0),
            new Count(0)
        );
    }
}
