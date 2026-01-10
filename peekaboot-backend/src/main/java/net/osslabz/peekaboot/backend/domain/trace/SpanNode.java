package net.osslabz.peekaboot.backend.domain.trace;

import java.util.List;
import java.util.Map;

public record SpanNode(
    String spanId,
    String name,
    String kind,
    long startTimeMs,
    long durationMs,
    String status,
    List<SpanNode> children,
    Map<String, Object> attributes,
    List<SpanIssue> issues
) {}
