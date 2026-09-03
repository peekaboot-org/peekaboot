package org.peekaboot.backend.domain.trace;

/** The per-trace counts the toolbar's badges and the Traces tab's list rows show, one sub-record per tab. */
public record TraceTabSummary(RequestSummary request, SpansSummary spans, QueriesSummary queries, LogsSummary logs) {
    public record RequestSummary(String method, String path, Integer statusCode) {}

    public record SpansSummary(int count, long totalDurationMs, int errorCount) {}

    public record QueriesSummary(int count, long totalDurationMs) {}

    public record LogsSummary(int count, int errorCount, int warnCount) {}
}
