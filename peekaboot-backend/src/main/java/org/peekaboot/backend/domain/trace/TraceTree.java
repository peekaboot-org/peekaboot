package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * One trace as the Traces tab renders it: its row in the list, the span tree behind it and
 * the detail each tab of the overlay shows.
 *
 * <p>Copy-on-write: the {@code with*} methods are how the stages after {@code TraceTreeMapper}
 * fill in what they add, so no stage re-lists the components it leaves alone.
 *
 * @param complete whether the trace's root has arrived; see {@code TraceData#complete}. Always
 *                 true on an async row, whose entry span is stored
 * @param outcome  {@code IN_PROGRESS} while incomplete, else {@code ERROR} when the trace failed by
 *                 the Errors rule. An async row is judged by its own subtree's reasons
 * @param rootSpan the span tree; null while the trace is incomplete, and on a complete trace
 *                 whose root the span cap evicted
 * @param orphans  the stored subtrees of a trace whose root is null, earliest start first, holding
 *                 every stored span between them; empty otherwise, where a subtree whose parent is
 *                 missing hangs under the root instead
 * @param buckets  the Errors and Slow buckets listing the trace, in that order; an async row
 *                 carries its trace's
 * @param reasons  why the trace failed or ran slow; an async row's come from its subtree alone
 */
public record TraceTree(
        String traceId,
        Instant startTime,
        long durationMs,
        boolean complete,
        TraceOutcome outcome,
        RootActionType rootActionType,
        String rootOperation,
        SpanNode rootSpan,
        List<SpanNode> orphans,
        List<TraceBucket> buckets,
        List<TraceReason> reasons,
        TraceTabSummary summary,
        HttpExchange httpExchange,
        List<TraceLog> logs,
        List<QueryInfo> queries,
        SubtreeView subtree,
        boolean truncated) {

    /**
     * A trace as {@code TraceTreeMapper} lists it, not yet judged. {@link #withVerdict} adds the
     * outcome, the buckets and the reasons, and {@link #withDetails} the overlay's detail.
     */
    public TraceTree(
            String traceId,
            Instant startTime,
            long durationMs,
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
                complete,
                TraceOutcome.of(complete, false),
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                List.of(),
                List.of(),
                summary,
                null,
                List.of(),
                List.of(),
                subtree,
                truncated);
    }

    public TraceTree withSpans(SpanNode newRootSpan, List<SpanNode> newOrphans) {
        return new TraceTree(
                traceId,
                startTime,
                durationMs,
                complete,
                outcome,
                rootActionType,
                rootOperation,
                newRootSpan,
                newOrphans,
                buckets,
                reasons,
                summary,
                httpExchange,
                logs,
                queries,
                subtree,
                truncated);
    }

    /** The judgement on the whole tree, made once its spans and logs are in hand. */
    public TraceTree withVerdict(TraceOutcome newOutcome, List<TraceBucket> newBuckets, List<TraceReason> newReasons) {
        return new TraceTree(
                traceId,
                startTime,
                durationMs,
                complete,
                newOutcome,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                newBuckets,
                newReasons,
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
                complete,
                outcome,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                buckets,
                reasons,
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
                complete,
                outcome,
                rootActionType,
                rootOperation,
                rootSpan,
                orphans,
                buckets,
                reasons,
                summary,
                newHttpExchange,
                newLogs,
                newQueries,
                subtree,
                truncated);
    }
}
