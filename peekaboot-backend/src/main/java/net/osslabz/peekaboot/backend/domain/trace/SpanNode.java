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
    Map<String, Object> tags,
    List<SpanEvent> events,
    List<SpanIssue> issues,
    long creationOrder,
    String errorMessage,
    String errorClass,
    String remoteServiceName
) {
    /**
     * Convenience constructor without the new fields (for backwards compatibility in tests).
     */
    public SpanNode(
            String spanId,
            String name,
            String kind,
            long startTimeMs,
            long durationMs,
            String status,
            List<SpanNode> children,
            Map<String, Object> tags,
            List<SpanEvent> events,
            List<SpanIssue> issues
    ) {
        this(spanId, name, kind, startTimeMs, durationMs, status, children, tags, events, issues, 0, null, null, null);
    }
}
