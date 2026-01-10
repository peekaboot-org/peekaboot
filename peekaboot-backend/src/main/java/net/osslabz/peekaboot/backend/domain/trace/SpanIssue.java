package net.osslabz.peekaboot.backend.domain.trace;

public record SpanIssue(
    IssueType type,
    String message,
    String severity
) {}
