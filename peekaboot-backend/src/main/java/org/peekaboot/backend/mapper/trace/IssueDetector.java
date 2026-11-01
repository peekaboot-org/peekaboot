package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;

public class IssueDetector {

    private final PeekabootTracingProperties properties;

    public IssueDetector(PeekabootTracingProperties properties) {
        this.properties = properties;
    }

    public TraceTree detectIssues(TraceTree trace) {
        SpanNode root = trace.rootSpan() == null ? null : processSpan(trace.rootSpan());
        List<SpanNode> orphans = trace.orphans().stream().map(this::processSpan).toList();
        boolean slow = (root != null && hasSlowIssue(root)) || orphans.stream().anyMatch(IssueDetector::hasSlowIssue);
        return trace.withSpans(root, orphans, slow);
    }

    private SpanNode processSpan(SpanNode span) {
        List<SpanIssue> issues = new ArrayList<>();
        long selfMs = span.selfMs();
        long verySlowMs = properties.getVerySlowSpanThresholdMs();
        long slowMs = properties.getSlowSpanThresholdMs();
        long slowQueryMs = properties.getSlowQueryThresholdMs();

        if (selfMs >= verySlowMs) {
            issues.add(new SpanIssue(IssueType.VERY_SLOW, IssueSeverity.ERROR));
        } else if (selfMs >= slowMs) {
            issues.add(new SpanIssue(IssueType.SLOW, IssueSeverity.WARNING));
        }

        if (DbSpans.isQuery(span) && span.durationMs() >= slowQueryMs) {
            issues.add(new SpanIssue(IssueType.SLOW_QUERY, IssueSeverity.WARNING));
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
}
