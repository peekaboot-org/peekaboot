package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.CapturedLogs;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/** Assembles one row from a stored bundle with the mappers {@link TraceResources} built. */
final class TraceRows {

    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final TraceLogMapper traceLogMapper;
    private final TraceSummaries traceSummaries;
    private final TraceDetails traceDetails;

    TraceRows(
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            TraceLogMapper traceLogMapper,
            TraceSummaries traceSummaries,
            TraceDetails traceDetails) {
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.traceLogMapper = traceLogMapper;
        this.traceSummaries = traceSummaries;
        this.traceDetails = traceDetails;
    }

    /** See {@link TraceResources#row}. */
    TraceResource of(
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
