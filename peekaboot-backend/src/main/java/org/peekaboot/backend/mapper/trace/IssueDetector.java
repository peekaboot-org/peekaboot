package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceTree;

public class IssueDetector {

    private final UiTracingProperties properties;

    public IssueDetector(UiTracingProperties properties) {
        this.properties = properties;
    }

    public TraceTree detectIssues(TraceTree trace) {
        if (trace.rootSpan() == null) {
            return trace;
        }

        SpanNode processedRoot = processSpan(trace.rootSpan());

        return trace.withRootSpan(processedRoot, hasSlowIssue(processedRoot));
    }

    private SpanNode processSpan(SpanNode span) {
        List<SpanIssue> issues = new ArrayList<>();

        if (span.durationMs() >= properties.getVerySlowSpanThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.VERY_SLOW,
                    String.format(
                            "Span took %dms (threshold: %dms)",
                            span.durationMs(), properties.getVerySlowSpanThresholdMs()),
                    IssueSeverity.ERROR));
        } else if (span.durationMs() >= properties.getSlowSpanThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.SLOW,
                    String.format(
                            "Span took %dms (threshold: %dms)", span.durationMs(), properties.getSlowSpanThresholdMs()),
                    IssueSeverity.WARNING));
        }

        if (span.status() == SpanStatus.ERROR) {
            issues.add(new SpanIssue(IssueType.ERROR, getErrorMessage(span), IssueSeverity.ERROR));
        }

        if (DbSpans.isQuery(span) && span.durationMs() >= properties.getSlowQueryThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.SLOW_QUERY,
                    String.format(
                            "Query took %dms (threshold: %dms)",
                            span.durationMs(), properties.getSlowQueryThresholdMs()),
                    IssueSeverity.WARNING));
        }

        List<SpanNode> processedChildren =
                span.children().stream().map(this::processSpan).toList();

        return span.withIssues(issues, processedChildren);
    }

    private static boolean hasSlowIssue(SpanNode span) {
        return span.issues().stream()
                        .anyMatch(issue -> issue.type() == IssueType.SLOW || issue.type() == IssueType.VERY_SLOW)
                || span.children().stream().anyMatch(IssueDetector::hasSlowIssue);
    }

    private String getErrorMessage(SpanNode span) {
        if (span.errorMessage() != null && !span.errorMessage().isBlank()) {
            return span.errorMessage();
        }
        String taggedMessage = span.tags().get("error.message");
        return taggedMessage != null ? taggedMessage : "Span ended with error";
    }
}
