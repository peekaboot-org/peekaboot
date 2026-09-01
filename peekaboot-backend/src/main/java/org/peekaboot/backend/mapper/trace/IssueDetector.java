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
import org.springframework.stereotype.Component;

@Component
public class IssueDetector {

    private final UiTracingProperties properties;

    public IssueDetector(UiTracingProperties properties) {
        this.properties = properties;
    }

    public TraceTree detectIssues(TraceTree trace) {
        if (trace.rootSpan() == null) {
            return trace;
        }

        int traceDbQueryCount = trace.summary() != null && trace.summary().queries() != null
                ? trace.summary().queries().count()
                : 0;
        SpanNode processedRoot = processSpan(trace.rootSpan(), true, traceDbQueryCount);

        return new TraceTree(
                trace.traceId(),
                trace.startTimeMs(),
                trace.durationMs(),
                trace.status(),
                hasSlowIssue(processedRoot),
                trace.rootActionType(),
                trace.rootOperation(),
                processedRoot,
                trace.summary(),
                trace.httpExchange(),
                trace.logs(),
                trace.queries(),
                trace.truncated());
    }

    private SpanNode processSpan(SpanNode span, boolean isRoot, int traceDbQueryCount) {
        List<SpanIssue> issues = new ArrayList<>();

        // Check for VERY_SLOW (before SLOW so we don't add both)
        if (span.durationMs() >= properties.getVerySlowSpanThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.VERY_SLOW,
                    String.format(
                            "Span took %dms (threshold: %dms)",
                            span.durationMs(), properties.getVerySlowSpanThresholdMs()),
                    IssueSeverity.ERROR));
        } else if (span.durationMs() >= properties.getSlowSpanThresholdMs()) {
            // Check for SLOW (only if not VERY_SLOW)
            issues.add(new SpanIssue(
                    IssueType.SLOW,
                    String.format(
                            "Span took %dms (threshold: %dms)", span.durationMs(), properties.getSlowSpanThresholdMs()),
                    IssueSeverity.WARNING));
        }

        // Check for ERROR
        if (span.status() == SpanStatus.ERROR) {
            issues.add(new SpanIssue(IssueType.ERROR, getErrorMessage(span), IssueSeverity.ERROR));
        }

        // Check for SLOW_QUERY (if DB query span)
        if (DbSpans.isQuery(span) && span.durationMs() >= properties.getSlowQueryThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.SLOW_QUERY,
                    String.format(
                            "Query took %dms (threshold: %dms)",
                            span.durationMs(), properties.getSlowQueryThresholdMs()),
                    IssueSeverity.WARNING));
        }

        // Check for HIGH_QUERY_COUNT on trace level (only on root span)
        if (isRoot && traceDbQueryCount > properties.getHighTraceQueryCountThreshold()) {
            issues.add(new SpanIssue(
                    IssueType.HIGH_QUERY_COUNT,
                    String.format(
                            "Trace has %d database queries (threshold: %d)",
                            traceDbQueryCount, properties.getHighTraceQueryCountThreshold()),
                    IssueSeverity.WARNING));
        }

        // Check for HIGH_QUERY_COUNT per span (many direct query children)
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

        // Recursively process children
        List<SpanNode> processedChildren = span.children().stream()
                .map(child -> processSpan(child, false, traceDbQueryCount))
                .toList();

        return new SpanNode(
                span.spanId(),
                span.name(),
                span.kind(),
                span.startTimeMs(),
                span.durationMs(),
                span.status(),
                processedChildren,
                span.tags(),
                span.events(),
                issues,
                span.creationOrder(),
                span.errorMessage(),
                span.errorClass(),
                span.remoteServiceName(),
                span.query(),
                span.logs());
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
        if (span.tags() != null) {
            Object errorMessage = span.tags().get("error.message");
            if (errorMessage != null) {
                return errorMessage.toString();
            }
        }
        return "Span ended with error";
    }
}
