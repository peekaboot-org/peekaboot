package org.peekaboot.backend.domain.trace;

import java.util.List;
import java.util.Map;

public record SpanNode(
        String spanId,
        String name,
        String kind,
        long startTimeMs,
        long durationMs,
        SpanStatus status,
        List<SpanNode> children,
        Map<String, Object> tags,
        List<SpanEvent> events,
        List<SpanIssue> issues,
        long creationOrder,
        String errorMessage,
        String errorClass,
        String remoteServiceName,
        String query,
        List<TraceLog> logs) {

    public SpanNode withLogs(List<TraceLog> newLogs) {
        return new SpanNode(
                spanId,
                name,
                kind,
                startTimeMs,
                durationMs,
                status,
                children,
                tags,
                events,
                issues,
                creationOrder,
                errorMessage,
                errorClass,
                remoteServiceName,
                query,
                newLogs);
    }

    public SpanNode withChildren(List<SpanNode> newChildren) {
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
                issues,
                creationOrder,
                errorMessage,
                errorClass,
                remoteServiceName,
                query,
                logs);
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
                remoteServiceName,
                query,
                logs);
    }
}
