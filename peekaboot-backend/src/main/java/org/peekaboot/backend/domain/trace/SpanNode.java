package org.peekaboot.backend.domain.trace;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;

public record SpanNode(
        String spanId,
        String name,
        Span.Kind kind,
        long startTimeMs,
        long durationMs,
        SpanStatus status,
        List<SpanNode> children,
        Map<String, String> tags,
        List<SpanEvent> events,
        List<SpanIssue> issues,
        long creationOrder,
        String errorMessage,
        String errorClass,
        SqlStatement query,
        Long rowCount,
        boolean asyncEntry) {

    /** Absent collections normalise to empty here, so no reader of a mapped span has to guard for null. */
    public SpanNode {
        children = children == null ? List.of() : children;
        tags = tags == null ? Map.of() : tags;
        events = events == null ? List.of() : events;
        issues = issues == null ? List.of() : issues;
    }

    /** The issues judged for this span, with the children the same judgement already ran over. */
    public SpanNode withIssues(List<SpanIssue> newIssues, List<SpanNode> newChildren) {
        return new SpanNode(
                spanId,
                name,
                kind,
                startTimeMs,
                durationMs,
                status,
                newChildren,
                tags,
                events,
                newIssues,
                creationOrder,
                errorMessage,
                errorClass,
                query,
                rowCount,
                asyncEntry);
    }
}
