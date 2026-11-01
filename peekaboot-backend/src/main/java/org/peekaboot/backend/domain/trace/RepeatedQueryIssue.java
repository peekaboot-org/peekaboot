package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * One statement a span ran at least the repeated-query threshold's number of times.
 *
 * @param spanId the direct parent of the queries
 * @param value how many times the statement ran, the same number as {@code count}
 * @param statement the masked statement with its literals replaced by {@code ?} and its whitespace collapsed
 * @param totalMs the queries' durations added up
 * @param spanIds the query spans in their parent's child order, which the mapper sorts by start
 */
public record RepeatedQueryIssue(
        IssueType type,
        IssueSeverity severity,
        String spanId,
        long value,
        long threshold,
        IssueUnit unit,
        String message,
        String statement,
        int count,
        long totalMs,
        List<String> spanIds)
        implements TraceIssue {}
