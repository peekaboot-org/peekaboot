package org.peekaboot.backend.domain.trace;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SpanNode(
        String spanId,
        String name,
        Span.Kind kind,
        Instant startTime,
        long durationMs,
        SpanStatus status,
        List<SpanNode> children,
        Map<String, String> tags,
        List<SpanEvent> events,
        List<SpanIssue> issues,
        String errorMessage,
        String errorClass,
        SqlStatement statement,
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
                startTime,
                durationMs,
                status,
                newChildren,
                tags,
                events,
                newIssues,
                errorMessage,
                errorClass,
                statement,
                rowCount,
                asyncEntry);
    }
}
