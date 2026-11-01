package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * What an agent reads first about a trace, and what the Traces tab's row and the overlay header show.
 *
 * @param startTime null while no timed span has arrived
 * @param request the root's HTTP request; null where the root is no HTTP request or has not arrived
 * @param buckets the Errors and Slow buckets listing the trace, in that order. All lists every trace and is never named
 * @param reasons why the trace failed or ran slow; see {@link TraceReason}
 * @param cause the first failure to look at; null where the trace did not fail, where the caps evicted what failed it,
 *     or where its only failure is a span that names nothing
 * @param issues every issue on the trace's spans, in the order the Spans tab draws them
 * @param subtree null for a whole trace; on a listing row for an async task, the span it is rooted at
 */
public record TraceSummary(
        String traceId,
        @Nullable Instant startTime,
        long durationMs,
        boolean complete,
        RootActionType rootActionType,
        @Nullable String rootOperation,
        @Nullable Request request,
        TraceOutcome outcome,
        List<TraceBucket> buckets,
        List<TraceReason> reasons,
        @Nullable Cause cause,
        List<TraceIssue> issues,
        Counts counts,
        @Nullable SubtreeView subtree) {

    /**
     * The root span's HTTP request.
     *
     * @param route the matched route pattern, such as {@code /persons/{id}}; null where no handler matched
     */
    public record Request(
            @Nullable String method,
            @Nullable String route,
            @Nullable String path,
            @Nullable Integer statusCode) {}

    /**
     * Where to start reading a failed trace.
     *
     * @param type the exception's class; null for a 5xx or a failure that named none
     * @param applicationFrame the top frame inside the application packages, without its {@code at} prefix
     * @param spanId the failing span, or the span the cause was logged on
     */
    public record Cause(
            @Nullable String type,
            @Nullable String message,
            @Nullable String applicationFrame,
            @Nullable String spanId) {}

    /**
     * What the trace holds and what its caps dropped.
     *
     * @param spans the spans stored; on a subtree row, the subtree's own
     * @param droppedSpans the spans the span cap evicted, which no count here includes
     * @param droppedLogs the logs the log cap trimmed
     */
    public record Counts(int spans, QueryCounts queries, LogCounts logs, int droppedSpans, int droppedLogs) {}

    public record QueryCounts(int count, long totalDurationMs) {}

    public record LogCounts(int count, int errorCount, int warnCount) {}
}
