package org.peekaboot.backend.domain.trace;

import java.util.List;
import java.util.Map;

public record TraceTree(
    String traceId,
    long startTimeMs,
    long durationMs,
    TraceStatus status,
    RootActionType rootActionType,
    String rootOperation,
    SpanNode rootSpan,
    TraceTabSummary summary,
    Map<String, Object> inheritedAttributes,
    HttpExchange httpExchange,
    List<TraceLog> logs,
    List<QueryInfo> queries,
    boolean truncated
) {
    public TraceTree(
            String traceId,
            long startTimeMs,
            long durationMs,
            TraceStatus status,
            RootActionType rootActionType,
            String rootOperation,
            SpanNode rootSpan,
            TraceTabSummary summary,
            Map<String, Object> inheritedAttributes
    ) {
        this(traceId, startTimeMs, durationMs, status, rootActionType, rootOperation,
                rootSpan, summary, inheritedAttributes, null, null, null, false);
    }
}
