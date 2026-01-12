package net.osslabz.peekaboot.backend.domain.trace;

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
    TraceMetrics metrics,
    Map<String, Object> inheritedAttributes,
    RequestDetails request,
    List<TraceLog> logs
) {
    public TraceTree(
            String traceId,
            long startTimeMs,
            long durationMs,
            TraceStatus status,
            RootActionType rootActionType,
            String rootOperation,
            SpanNode rootSpan,
            TraceMetrics metrics,
            Map<String, Object> inheritedAttributes
    ) {
        this(traceId, startTimeMs, durationMs, status, rootActionType, rootOperation,
                rootSpan, metrics, inheritedAttributes, null, null);
    }
}
