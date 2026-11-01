package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
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
    private final QueryExtractor queryExtractor;
    private final TraceLogMapper traceLogMapper;
    private final long slowTraceThresholdMs;

    public TraceResources(
            MaskingEngine maskingEngine,
            SqlFormatter sqlFormatter,
            PeekabootTracingProperties tracingProperties,
            StackTraceRules stackTraceRules) {
        this.traceTreeMapper = new TraceTreeMapper(maskingEngine, sqlFormatter, stackTraceRules);
        this.issueDetector = new IssueDetector(tracingProperties);
        this.queryExtractor = new QueryExtractor();
        this.traceLogMapper = new TraceLogMapper(maskingEngine, stackTraceRules);
        this.slowTraceThresholdMs = tracingProperties.getSlowTraceThresholdMs();
    }

    /** The whole trace with the overlay's detail, its request, logs and queries. */
    public TraceTree trace(TraceDataBundle bundle, Set<TraceBucket> buckets) {
        TraceData traceData = bundle.snapshot();
        TraceQueries traceQueries = traceTreeMapper.queriesOf(traceData);
        List<QueryInfo> queries = queryExtractor.extract(traceData, traceQueries);
        TraceTree tree = issueDetector.detectIssues(traceTreeMapper.map(traceData, traceQueries));
        RequestCompletedEvent request = bundle.request();
        // an @Async task may log meanwhile, so the badges and the list count one read
        List<LogCapturedEvent> logs = bundle.logs();
        List<TraceLog> mappedLogs = traceLogMapper.map(bundle, logs);
        // the mapped list keeps each log at its index, so LogCapturedEvent.isError stays the one ERROR test
        List<TraceLog> errorLogs = IntStream.range(0, logs.size())
                .filter(index -> logs.get(index).isError())
                .mapToObj(mappedLogs::get)
                .toList();
        return withVerdict(traceLogMapper.withLogsSummary(tree, logs), errorLogs, traceData.failed(), buckets)
                .withDetails(request != null ? HttpExchangeMapper.map(request) : null, mappedLogs, queries);
    }

    /**
     * A listing row, the whole trace or the async subtree {@code subtreeRootSpanId} names, without
     * the overlay's detail.
     */
    public TraceTree row(TraceDataBundle bundle, @Nullable String subtreeRootSpanId, Set<TraceBucket> buckets) {
        TraceData traceData = bundle.snapshot();
        if (subtreeRootSpanId == null) {
            List<LogCapturedEvent> logs = bundle.logs();
            TraceTree whole = issueDetector.detectIssues(traceTreeMapper.map(traceData));
            return withVerdict(
                    traceLogMapper.withLogsSummary(whole, logs), errorLogs(bundle, logs), traceData.failed(), buckets);
        }
        TraceTree subtree = issueDetector.detectIssues(traceTreeMapper.mapSubtree(traceData, subtreeRootSpanId));
        List<LogCapturedEvent> logs = traceLogMapper.logsWithin(subtree.rootSpan(), bundle);
        // the store's failure flag covers the enclosing request, which a subtree row does not report
        return withVerdict(traceLogMapper.withLogsSummary(subtree, logs), errorLogs(bundle, logs), false, buckets);
    }

    private List<TraceLog> errorLogs(TraceDataBundle bundle, List<LogCapturedEvent> logs) {
        return traceLogMapper.map(
                bundle, logs.stream().filter(LogCapturedEvent::isError).toList());
    }

    private TraceTree withVerdict(
            TraceTree tree, List<TraceLog> errorLogs, boolean storeFailed, Set<TraceBucket> buckets) {
        List<TraceReason> reasons = TraceReasons.of(tree, errorLogs, slowTraceThresholdMs);
        boolean failed =
                storeFailed || reasons.stream().anyMatch(reason -> reason.kind().failure());
        return tree.withVerdict(TraceOutcome.of(tree.complete(), failed), List.copyOf(buckets), reasons);
    }
}
