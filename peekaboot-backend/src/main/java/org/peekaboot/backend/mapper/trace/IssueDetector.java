package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
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

        SpanNode processedRoot = withTraceQueryCountIssue(processSpan(trace.rootSpan()), trace.summary());

        return trace.withRootSpan(processedRoot, hasSlowIssue(processedRoot));
    }

    /** The one trace-level issue, carried by the root span since the tree has nowhere else to show it. */
    private SpanNode withTraceQueryCountIssue(SpanNode root, TraceTabSummary summary) {
        int traceDbQueryCount =
                summary != null && summary.queries() != null ? summary.queries().count() : 0;
        if (traceDbQueryCount <= properties.getHighTraceQueryCountThreshold()) {
            return root;
        }
        List<SpanIssue> issues = new ArrayList<>(root.issues());
        issues.add(new SpanIssue(
                IssueType.HIGH_QUERY_COUNT,
                String.format(
                        "Trace has %d database queries (threshold: %d)",
                        traceDbQueryCount, properties.getHighTraceQueryCountThreshold()),
                IssueSeverity.WARNING));
        return root.withIssues(issues, root.children());
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

        long directQueryChildren =
                span.children().stream().filter(DbSpans::isQuery).count();
        if (directQueryChildren > properties.getHighQueryCountThreshold()) {
            issues.add(new SpanIssue(
                    IssueType.HIGH_QUERY_COUNT,
                    String.format(
                            "Span has %d direct database queries (threshold: %d)",
                            directQueryChildren, properties.getHighQueryCountThreshold()),
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
