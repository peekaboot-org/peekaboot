package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;

/**
 * One trace as the Traces tab renders it: its row in the list, the span tree behind it and
 * the detail each tab of the overlay shows.
 *
 * <p>Copy-on-write: the {@code with*} methods are how the stages after {@code TraceTreeMapper}
 * fill in what they add, so no stage re-lists the components it leaves alone.
 *
 * @param complete whether the trace's root has arrived; see {@code TraceData#complete}. Always
 *                 true on an async row, whose entry span is stored, so there it and
 *                 {@code outcome} describe the subtree
 * @param outcome  {@code IN_PROGRESS} while incomplete, else {@code ERROR} or {@code OK} by {@code status}
 * @param slow whether any span carries a SLOW or VERY_SLOW issue - the Traces tab's SLOW
 *             badge. A per-span judgement at the span thresholds, distinct from the Slow
 *             bucket, which admits a trace by its total duration.
 * @param rootSpan the span tree; null while the trace is incomplete, and on a complete trace
 *                 whose root the span cap evicted
 * @param orphans  the stored subtrees of a trace whose root is null, earliest start first, holding
 *                 every stored span between them; empty otherwise, where a subtree whose parent is
 *                 missing hangs under the root instead
 */
public record TraceTree(
        String traceId,
        Instant startTime,
        long durationMs,
        TraceStatus status,
        boolean complete,
        TraceOutcome outcome,
        boolean slow,
        RootActionType rootActionType,
        String rootOperation,
        SpanNode rootSpan,
        List<SpanNode> orphans,
        TraceTabSummary summary,
        HttpExchange httpExchange,
        List<TraceLog> logs,
        List<QueryInfo> queries,
        SubtreeView subtree,
        boolean truncated) {

    /**
     * A trace as {@code TraceTreeMapper} lists it: not yet judged slow, which {@code IssueDetector}
     * decides once the issues exist, and without the overlay's detail, which {@link #withDetails} adds.
     */
    public TraceTree(
            String traceId,
            Instant startTime,
            long durationMs,
            TraceStatus status,
            boolean complete,
            RootActionType rootActionType,
            String rootOperation,
            SpanNode rootSpan,
            List<SpanNode> orphans,
            TraceTabSummary summary,
            SubtreeView subtree,
            boolean truncated) {
        this(
                traceId,
                startTime,
                durationMs,
                status,
                complete,
                TraceOutcome.of(complete, status),
                false,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                summary,
                null,
                List.of(),
                List.of(),
                subtree,
                truncated);
    }

    /** The span tree and the orphans replaced, together with the badge judged from them. */
    public TraceTree withSpans(SpanNode newRootSpan, List<SpanNode> newOrphans, boolean newSlow) {
        return new TraceTree(
                traceId,
                startTime,
                durationMs,
                status,
                complete,
                outcome,
                newSlow,
                rootActionType,
                rootOperation,
                newRootSpan,
                newOrphans,
                summary,
                httpExchange,
                logs,
                queries,
                subtree,
                truncated);
    }

    public TraceTree withSummary(TraceTabSummary newSummary) {
        return new TraceTree(
                traceId,
                startTime,
                durationMs,
                status,
                complete,
                outcome,
                slow,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                newSummary,
                httpExchange,
                logs,
                queries,
                subtree,
                truncated);
    }

    /** The overlay's detail: the request, the flat log list and the extracted queries. */
    public TraceTree withDetails(HttpExchange newHttpExchange, List<TraceLog> newLogs, List<QueryInfo> newQueries) {
        return new TraceTree(
                traceId,
                startTime,
                durationMs,
                status,
                complete,
                outcome,
                slow,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                summary,
                newHttpExchange,
                newLogs,
                newQueries,
                subtree,
                truncated);
    }
}
