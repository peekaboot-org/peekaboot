package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * One mapped trace as {@code TraceResources} assembles it: the judged span tree and what the
 * summary reads off the spans. Never served as it is.
 *
 * @param run      the start of the trace's run; see {@link TraceData#run()}
 * @param interrupted see {@link TraceData#interrupted()}
 * @param request  the root's HTTP request; null where the root is no HTTP request or has not arrived
 * @param rootSpan the span tree; null while the trace is incomplete, and on a complete trace whose root the span cap evicted
 * @param orphans  the stored subtrees while {@code rootSpan} is null, earliest start first; empty otherwise
 * @param spanCount the spans stored, or on a subtree the subtree's own
 * @param subtree  null for a whole trace; set where this is one async subtree of it
 */
public record TraceTree(
        String traceId,
        @Nullable Instant run,
        Instant startTime,
        long durationMs,
        boolean complete,
        boolean interrupted,
        RootActionType rootActionType,
        String rootOperation,
        TraceSummary.Request request,
        SpanNode rootSpan,
        List<SpanNode> orphans,
        int spanCount,
        TraceSummary.QueryCounts queries,
        SubtreeView subtree) {

    /** The span tree and the orphans replaced, once their issues are judged. */
    public TraceTree withSpans(SpanNode newRootSpan, List<SpanNode> newOrphans) {
        return new TraceTree(
                traceId,
                run,
                startTime,
                durationMs,
                complete,
                interrupted,
                rootActionType,
                rootOperation,
                request,
                newRootSpan,
                newOrphans,
                spanCount,
                queries,
                subtree);
    }
}
