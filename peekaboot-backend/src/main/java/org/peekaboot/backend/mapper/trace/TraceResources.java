package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.CapturedLogs;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/**
 * Turns a stored bundle into the trace the API serves, whole or as one listing row. Every mapper is
 * built here rather than injected, as in {@code ActuatorInsightsService}, so this is the one place
 * the masking policy enters the trace pipeline.
 */
public final class TraceResources {

    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final TraceLogMapper traceLogMapper;
    private final TraceSummaries traceSummaries;
    private final TraceDetails traceDetails;

    public TraceResources(
            MaskingEngine maskingEngine,
            SqlFormatter sqlFormatter,
            PeekabootTracingProperties tracingProperties,
            StackTraceRules stackTraceRules) {
        this.traceTreeMapper = new TraceTreeMapper(maskingEngine, sqlFormatter, stackTraceRules);
        this.issueDetector = new IssueDetector(tracingProperties);
        this.traceLogMapper = new TraceLogMapper(maskingEngine, stackTraceRules);
        this.traceSummaries = new TraceSummaries(tracingProperties);
        this.traceDetails = new TraceDetails();
    }

    /** The whole trace, its summary and its data. */
    public TraceResource trace(TraceDataBundle bundle, Set<TraceBucket> buckets) {
        return row(bundle, null, buckets, TraceView.FULL);
    }

    /**
     * One listing row, the whole trace or the async subtree {@code subtreeRootSpanId} names. A subtree
     * whose entry span was evicted since the listing maps as the whole trace.
     */
    public TraceResource row(
            TraceDataBundle bundle, @Nullable String subtreeRootSpanId, Set<TraceBucket> buckets, TraceView view) {
        TraceData traceData = bundle.snapshot();
        TraceQueries queries = traceTreeMapper.queriesOf(traceData);
        TraceTree tree = issueDetector.detectIssues(
                subtreeRootSpanId == null
                        ? traceTreeMapper.map(traceData, queries)
                        : traceTreeMapper.mapSubtree(traceData, subtreeRootSpanId, queries));
        boolean whole = tree.subtree() == null;
        // an @Async task may log meanwhile, so the counts and the list come from one read
        CapturedLogs captured = bundle.capturedLogs();
        UnaryOperator<String> spanOf = TraceLogMapper.resolvingOnce(bundle);
        List<LogCapturedEvent> logs =
                whole ? captured.logs() : TraceLogMapper.logsWithin(tree.rootSpan(), spanOf, captured.logs());
        boolean full = view == TraceView.FULL;
        List<TraceLog> mapped = traceLogMapper.map(
                spanOf,
                full ? logs : logs.stream().filter(LogCapturedEvent::isError).toList());
        TraceFacts facts = new TraceFacts(
                // the store's flag covers the enclosing request, which a subtree row does not report
                whole && traceData.failed(),
                buckets,
                logs,
                full ? TraceLogMapper.errorsAmong(logs, mapped) : mapped,
                traceData.droppedSpans(),
                captured.droppedLogs());
        return new TraceResource(
                traceSummaries.of(tree, facts),
                full ? traceDetails.of(bundle, traceData, queries, tree, mapped) : null);
    }
}
