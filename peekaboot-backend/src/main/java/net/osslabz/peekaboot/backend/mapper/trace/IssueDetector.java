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

        int traceDbQueryCount = trace.metrics().dbQueryCount();
        SpanNode processedRoot = processSpan(trace.rootSpan(), true, traceDbQueryCount);

        return new TraceTree(
                trace.traceId(),
                trace.startTimeMs(),
                trace.durationMs(),
                trace.status(),
                trace.rootActionType(),
                trace.rootOperation(),
                processedRoot,
                trace.metrics(),
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

        // Check for SLOW_QUERY (if DB span)
        if (isDbSpan(span) && span.durationMs() >= properties.getSlowQueryThresholdMs()) {
            issues.add(new SpanIssue(
                    IssueType.SLOW_QUERY,
                    String.format("Query took %dms (threshold: %dms)",
                            span.durationMs(), properties.getSlowQueryThresholdMs()),
                    "warning"
            ));
        }

        // Check for HIGH_QUERY_COUNT (only on root span)
        if (isRoot && traceDbQueryCount > properties.getHighTraceQueryCountThreshold()) {
            issues.add(new SpanIssue(
                    IssueType.HIGH_QUERY_COUNT,
                    String.format("Trace has %d database queries (threshold: %d)",
                            traceDbQueryCount, properties.getHighTraceQueryCountThreshold()),
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

    private boolean isDbSpan(SpanNode span) {
        if (span.tags() == null) {
            return false;
        }
        return span.tags().keySet().stream()
                .anyMatch(key -> key.startsWith("db.") || key.startsWith("jdbc."));
    }

    private String getErrorMessage(SpanNode span) {
        if (span.tags() != null) {
            Object errorMessage = span.tags().get("error.message");
            if (errorMessage != null) {
                return errorMessage.toString();
            }
        }
        return "Span ended with error";
    }
}
