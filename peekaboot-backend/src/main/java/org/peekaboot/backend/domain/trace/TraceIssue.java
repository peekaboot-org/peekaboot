package org.peekaboot.backend.domain.trace;

/** One problem on one span. {@link RepeatedQueryIssue} adds the fields its type needs; every other type is a {@link SpanIssue}. */
public sealed interface TraceIssue permits SpanIssue, RepeatedQueryIssue {

    IssueType type();

    IssueSeverity severity();

    String spanId();

    long value();

    long threshold();

    IssueUnit unit();

    String message();
}
