package org.peekaboot.backend.domain.trace;

/**
 * Per-trace summary organized by UI tab categories.
 * Contains all information needed to render the dev toolbar and trace list.
 */
public record TraceTabSummary(
    RequestSummary request,
    SpansSummary spans,
    QueriesSummary queries,
    LogsSummary logs
) {
    public record RequestSummary(
        String method,
        String path,
        Integer statusCode
    ) {}

    public record SpansSummary(
        int count,
        long totalDurationMs,
        int errorCount
    ) {}

    public record QueriesSummary(
        int count,
        long totalDurationMs
    ) {}

    public record LogsSummary(
        int count,
        int errorCount,
        int warnCount
    ) {}
}
