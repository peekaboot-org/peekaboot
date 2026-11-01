package org.peekaboot.backend.domain.trace;

/**
 * One threshold a span reached.
 *
 * @param spanId the span the issue is on
 * @param value what the span measured, in {@code unit}
 * @param threshold the configured limit {@code value} reached
 * @param message one plain sentence that names the value
 */
public record SpanIssue(
        IssueType type,
        IssueSeverity severity,
        String spanId,
        long value,
        long threshold,
        IssueUnit unit,
        String message) {}
