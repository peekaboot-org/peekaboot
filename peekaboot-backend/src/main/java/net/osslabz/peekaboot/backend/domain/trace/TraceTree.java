package net.osslabz.peekaboot.backend.domain.trace;

import java.util.Map;

public record TraceTree(
    String traceId,
    long startTimeMs,
    long durationMs,
    TraceStatus status,
    String rootOperation,
    SpanNode rootSpan,
    TraceMetrics metrics,
    Map<String, Object> inheritedAttributes
) {}
