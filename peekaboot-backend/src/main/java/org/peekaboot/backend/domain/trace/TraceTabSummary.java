package org.peekaboot.backend.domain.trace;

/** The per-trace counts the toolbar's badges and the Traces tab's list rows show, one sub-record per tab. */
public record TraceTabSummary(RequestSummary request, SpansSummary spans, QueriesSummary queries, LogsSummary logs) {
    public record RequestSummary(String method, String path, Integer statusCode) {}

    public record SpansSummary(int count, long totalDurationMs, int errorCount) {}

    public record QueriesSummary(int count, long totalDurationMs) {}

    public record LogsSummary(int count, int errorCount, int warnCount) {}

    /** No request and every count zero: the summary of a trace with no spans. */
    public static TraceTabSummary empty() {
        return new TraceTabSummary(
                null, new SpansSummary(0, 0L, 0), new QueriesSummary(0, 0L), new LogsSummary(0, 0, 0));
    }
}
