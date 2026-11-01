package org.peekaboot.backend.mapper.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
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
    private final QueryExtractor queryExtractor;
    private final TraceLogMapper traceLogMapper;

    public TraceResources(
            MaskingEngine maskingEngine,
            SqlFormatter sqlFormatter,
            PeekabootTracingProperties tracingProperties,
            StackTraceRules stackTraceRules) {
        this.traceTreeMapper = new TraceTreeMapper(maskingEngine, sqlFormatter, stackTraceRules);
        this.issueDetector = new IssueDetector(tracingProperties);
        this.queryExtractor = new QueryExtractor();
        this.traceLogMapper = new TraceLogMapper(maskingEngine, stackTraceRules);
    }

    /** The whole trace with the overlay's detail, its request, logs and queries. */
    public TraceTree trace(TraceDataBundle bundle) {
        TraceData traceData = bundle.snapshot();
        TraceQueries traceQueries = traceTreeMapper.queriesOf(traceData);
        List<QueryInfo> queries = queryExtractor.extract(traceData, traceQueries);
        TraceTree tree = issueDetector.detectIssues(traceTreeMapper.map(traceData, traceQueries));
        RequestCompletedEvent request = bundle.request();
        // an @Async task may log meanwhile, so the badges and the list count one read
        List<LogCapturedEvent> logs = bundle.logs();
        return traceLogMapper
                .withLogsSummary(tree, logs)
                .withDetails(
                        request != null ? HttpExchangeMapper.map(request) : null,
                        traceLogMapper.map(bundle, logs),
                        queries);
    }

    /**
     * A listing row, the whole trace or the async subtree {@code subtreeRootSpanId} names, without
     * the overlay's detail.
     */
    public TraceTree row(TraceDataBundle bundle, @Nullable String subtreeRootSpanId) {
        TraceData traceData = bundle.snapshot();
        if (subtreeRootSpanId == null) {
            return issueDetector.detectIssues(
                    traceLogMapper.withLogsSummary(traceTreeMapper.map(traceData), bundle.logs()));
        }
        TraceTree subtree = traceTreeMapper.mapSubtree(traceData, subtreeRootSpanId);
        return issueDetector.detectIssues(
                traceLogMapper.withLogsSummary(subtree, traceLogMapper.logsWithin(subtree.rootSpan(), bundle)));
    }
}
