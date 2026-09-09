package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * One trace as the Traces tab renders it: its row in the list, the span tree behind it and
 * the detail each tab of the overlay shows.
 *
 * <p>Copy-on-write: the {@code with*} methods are how the stages after {@code TraceTreeMapper}
 * fill in what they add, so no stage re-lists the components it leaves alone.
 *
 * @param slow whether any span carries a SLOW or VERY_SLOW issue - the Traces tab's SLOW
 *             badge. A per-span judgement at the span thresholds, distinct from the Slow
 *             bucket, which admits a trace by its total duration.
 */
public record TraceTree(
        String traceId,
        long startTimeMs,
        long durationMs,
        TraceStatus status,
        boolean slow,
        RootActionType rootActionType,
        String rootOperation,
        SpanNode rootSpan,
        TraceTabSummary summary,
        HttpExchange httpExchange,
        List<TraceLog> logs,
        List<QueryInfo> queries,
        boolean truncated) {

    /** The span tree replaced, together with the badge judged from it. */
    public TraceTree withRootSpan(SpanNode newRootSpan, boolean newSlow) {
        return new TraceTree(
                traceId,
                startTimeMs,
                durationMs,
                status,
                newSlow,
                rootActionType,
                rootOperation,
                newRootSpan,
                summary,
                httpExchange,
                logs,
                queries,
                truncated);
    }

    public TraceTree withSummary(TraceTabSummary newSummary) {
        return new TraceTree(
                traceId,
                startTimeMs,
                durationMs,
                status,
                slow,
                rootActionType,
                rootOperation,
                rootSpan,
                newSummary,
                httpExchange,
                logs,
                queries,
                truncated);
    }

    /** The overlay's detail: the request, the flat log list and the extracted queries. */
    public TraceTree withDetails(HttpExchange newHttpExchange, List<TraceLog> newLogs, List<QueryInfo> newQueries) {
        return new TraceTree(
                traceId,
                startTimeMs,
                durationMs,
                status,
                slow,
                rootActionType,
                rootOperation,
                rootSpan,
                summary,
                newHttpExchange,
                newLogs,
                newQueries,
                truncated);
    }
}
