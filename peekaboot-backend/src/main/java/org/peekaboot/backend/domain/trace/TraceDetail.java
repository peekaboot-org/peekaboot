package org.peekaboot.backend.domain.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A trace's data, everything the overlay's tabs render once the summary has been read.
 *
 * @param rootSpan the span tree; null while the trace is incomplete, and on a complete trace whose root the span cap evicted
 * @param orphans the stored subtrees while {@code rootSpan} is null, earliest start first; empty otherwise
 * @param httpExchange the captured request and response; null on a subtree row and where none was captured
 */
public record TraceDetail(
        @Nullable SpanNode rootSpan,
        List<SpanNode> orphans,
        List<TraceLog> logs,
        List<QueryInfo> queries,
        @Nullable HttpExchange httpExchange) {}
