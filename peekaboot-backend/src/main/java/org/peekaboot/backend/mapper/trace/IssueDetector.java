package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.IssueUnit;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

public class IssueDetector {

    private final PeekabootTracingProperties properties;

    public IssueDetector(PeekabootTracingProperties properties) {
        this.properties = properties;
    }

    public TraceTree detectIssues(TraceTree trace) {
        SpanNode root = trace.rootSpan() == null ? null : withClientError(processSpan(trace.rootSpan()));
        List<SpanNode> orphans = trace.orphans().stream().map(this::processSpan).toList();
        boolean slow = (root != null && hasSlowIssue(root)) || orphans.stream().anyMatch(IssueDetector::hasSlowIssue);
        return trace.withSpans(root, orphans, slow);
    }

    private SpanNode processSpan(SpanNode span) {
        List<TraceIssue> issues = new ArrayList<>();
        long selfMs = span.selfMs();
        long verySlowMs = properties.getVerySlowSpanThresholdMs();
        long slowMs = properties.getSlowSpanThresholdMs();
        if (selfMs >= verySlowMs) {
            issues.add(selfTimeIssue(
                    span, IssueType.VERY_SLOW, IssueSeverity.ERROR, selfMs, verySlowMs, "very-slow-span"));
        } else if (selfMs >= slowMs) {
            issues.add(selfTimeIssue(span, IssueType.SLOW, IssueSeverity.WARNING, selfMs, slowMs, "slow-span"));
        }

        long queryMs = span.durationMs();
        long slowQueryMs = properties.getSlowQueryThresholdMs();
        if (DbSpans.isQuery(span) && queryMs >= slowQueryMs) {
            issues.add(new SpanIssue(
                    IssueType.SLOW_QUERY,
                    IssueSeverity.WARNING,
                    span.spanId(),
                    queryMs,
                    slowQueryMs,
                    IssueUnit.MS,
                    "This query took " + queryMs + " ms, at or above the " + slowQueryMs
                            + " ms slow-query threshold."));
        }
        issues.addAll(RepeatedQueries.under(span, properties.getRepeatedQueryThreshold()));

        List<SpanNode> processedChildren =
                span.children().stream().map(this::processSpan).toList();

        return span.withIssues(issues, processedChildren);
    }

    /**
     * Judges the root alone, the request the trace answers. An inner server span's 4xx answered a call
     * this application made to itself.
     */
    private static SpanNode withClientError(SpanNode root) {
        OptionalInt clientError = HttpSpanTags.clientErrorStatus(root.kind(), root.tags());
        if (clientError.isEmpty()) {
            return root;
        }
        int status = clientError.getAsInt();
        List<TraceIssue> issues = new ArrayList<>(root.issues());
        issues.add(new SpanIssue(
                IssueType.CLIENT_ERROR,
                IssueSeverity.WARNING,
                root.spanId(),
                status,
                HttpSpanTags.CLIENT_ERROR_FLOOR,
                IssueUnit.STATUS,
                "The request was answered with status " + status + "."));
        return root.withIssues(issues, root.children());
    }

    private static SpanIssue selfTimeIssue(
            SpanNode span,
            IssueType type,
            IssueSeverity severity,
            long selfMs,
            long thresholdMs,
            String thresholdName) {
        return new SpanIssue(
                type,
                severity,
                span.spanId(),
                selfMs,
                thresholdMs,
                IssueUnit.MS,
                "This span spent " + selfMs + " ms in its own work, at or above the " + thresholdMs + " ms "
                        + thresholdName + " threshold.");
    }

    private static boolean hasSlowIssue(SpanNode span) {
        return span.issues().stream()
                        .anyMatch(issue -> issue.type() == IssueType.SLOW || issue.type() == IssueType.VERY_SLOW)
                || span.children().stream().anyMatch(IssueDetector::hasSlowIssue);
    }
}
