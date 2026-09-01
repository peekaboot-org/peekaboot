package org.peekaboot.backend.domain.trace;

public record SpanIssue(IssueType type, String message, IssueSeverity severity) {}
