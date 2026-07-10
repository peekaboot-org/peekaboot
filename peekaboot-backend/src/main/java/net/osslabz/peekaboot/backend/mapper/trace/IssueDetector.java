package net.osslabz.peekaboot.backend.mapper.trace;

import net.osslabz.peekaboot.backend.config.UiTracingProperties;
import net.osslabz.peekaboot.backend.domain.trace.IssueType;
import net.osslabz.peekaboot.backend.domain.trace.SpanIssue;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
                ? trace.summary().queries().count() : 0;
        SpanNode processedRoot = processSpan(trace.rootSpan(), true, traceDbQueryCount);

        return new TraceTree(
                trace.traceId(),
                trace.startTimeMs(),
                trace.durationMs(),
                trace.status(),
                trace.rootActionType(),
                trace.rootOperation(),
                processedRoot,
                trace.summary(),
                trace.inheritedAttributes()
        );
    }

    private SpanNode processSpan(SpanNode span, boolean isRoot, int traceDbQueryCount) {
        List<SpanIssue> issues = new ArrayList<>();

        // Check for VERY_SLOW (before SLOW so we don't add both)
        if (span.durationMs() >= properties.getVerySlowSpanThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.VERY_SLOW,
                    String.format("Span took %dms (threshold: %dms)",
                            span.durationMs(), properties.getVerySlowSpanThresholdMs()),
                    "error"
            ));
        } else if (span.durationMs() >= properties.getSlowSpanThresholdMs()) {
            // Check for SLOW (only if not VERY_SLOW)
            issues.add(new SpanIssue(
                    IssueType.SLOW,
                    String.format("Span took %dms (threshold: %dms)",
                            span.durationMs(), properties.getSlowSpanThresholdMs()),
                    "warning"
            ));
        }

        // Check for ERROR
        if ("ERROR".equalsIgnoreCase(span.status())) {
            String errorMessage = getErrorMessage(span);
            issues.add(new SpanIssue(
                    IssueType.ERROR,
                    errorMessage,
                    "error"
            ));
        }

        // Check for SLOW_QUERY (if DB query span)
        if (isDbQuerySpan(span) && span.durationMs() >= properties.getSlowQueryThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.SLOW_QUERY,
                    String.format("Query took %dms (threshold: %dms)",
                            span.durationMs(), properties.getSlowQueryThresholdMs()),
                    "warning"
            ));
        }

        // Check for HIGH_QUERY_COUNT on trace level (only on root span)
        if (isRoot && traceDbQueryCount > properties.getHighTraceQueryCountThreshold()) {
            issues.add(new SpanIssue(
                    IssueType.HIGH_QUERY_COUNT,
                    String.format("Trace has %d database queries (threshold: %d)",
                            traceDbQueryCount, properties.getHighTraceQueryCountThreshold()),
                    "warning"
            ));
        }

        // Check for HIGH_QUERY_COUNT per span (many direct query children)
        long directQueryChildren = span.children().stream().filter(this::isDbQuerySpan).count();
        if (directQueryChildren > properties.getHighQueryCountThreshold()) {
            issues.add(new SpanIssue(
                    IssueType.HIGH_QUERY_COUNT,
                    String.format("Span has %d direct database queries (threshold: %d)",
                            directQueryChildren, properties.getHighQueryCountThreshold()),
                    "warning"
            ));
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
                span.remoteServiceName()
        );
    }

    /**
     * Actual query spans only - excludes datasource-proxy connection and
     * result-set spans (same definition as TraceTreeMapper's query summary).
     */
    private boolean isDbQuerySpan(SpanNode span) {
        if (span.tags() == null) {
            return false;
        }
        return span.tags().keySet().stream()
                .anyMatch(key -> key.startsWith("db.") || key.startsWith("jdbc.query"));
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
