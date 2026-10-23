package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
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
        long durationMs = span.durationMs();
        long verySlowMs = properties.getVerySlowSpanThresholdMs();
        long slowMs = properties.getSlowSpanThresholdMs();
        long slowQueryMs = properties.getSlowQueryThresholdMs();

        if (durationMs >= verySlowMs) {
            issues.add(issue(IssueType.VERY_SLOW, "Span", durationMs, verySlowMs, IssueSeverity.ERROR));
        } else if (durationMs >= slowMs) {
            issues.add(issue(IssueType.SLOW, "Span", durationMs, slowMs, IssueSeverity.WARNING));
        }

        if (DbSpans.isQuery(span) && durationMs >= slowQueryMs) {
            issues.add(issue(IssueType.SLOW_QUERY, "Query", durationMs, slowQueryMs, IssueSeverity.WARNING));
        }

        List<SpanNode> processedChildren =
                span.children().stream().map(this::processSpan).toList();

        return span.withIssues(issues, processedChildren);
    }

    private static SpanIssue issue(
            IssueType type, String subject, long durationMs, long thresholdMs, IssueSeverity severity) {
        return new SpanIssue(
                type, String.format("%s took %dms (threshold: %dms)", subject, durationMs, thresholdMs), severity);
    }

    private static boolean hasSlowIssue(SpanNode span) {
        return span.issues().stream()
                        .anyMatch(issue -> issue.type() == IssueType.SLOW || issue.type() == IssueType.VERY_SLOW)
                || span.children().stream().anyMatch(IssueDetector::hasSlowIssue);
    }
}
