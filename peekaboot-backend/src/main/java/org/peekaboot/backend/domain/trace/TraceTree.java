package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * One trace as the Traces tab renders it: its row in the list, the span tree behind it and
 * the detail each tab of the overlay shows.
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
        boolean truncated) {}
