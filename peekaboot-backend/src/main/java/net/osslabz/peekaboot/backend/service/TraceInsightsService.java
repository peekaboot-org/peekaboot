package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.domain.trace.IssueType;
import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceLog;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.mapper.trace.IssueDetector;
import net.osslabz.peekaboot.backend.mapper.trace.QueryExtractor;
import net.osslabz.peekaboot.backend.mapper.trace.TraceTreeMapper;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.TraceDataBundle;
import net.osslabz.peekaboot.tracing.store.TraceDataStorage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TraceInsightsService {

    private static final TraceInsightsResponse EMPTY_RESPONSE = new TraceInsightsResponse(
            List.of(),
            new TraceSummary(0, 0, 0, 0.0)
    );

    @Nullable
    private final TraceQueryService traceQueryService;
    @Nullable
    private final TraceDataStorage traceDataStorage;
    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final QueryExtractor queryExtractor;

    public TraceInsightsService(
            @Nullable TraceQueryService traceQueryService,
            @Nullable TraceDataStorage traceDataStorage,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor) {
        this.traceQueryService = traceQueryService;
        this.traceDataStorage = traceDataStorage;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
    }

    public TraceInsightsResponse getInsights(int limit, TraceCaptureMode mode) {
        if (traceQueryService == null) {
            return EMPTY_RESPONSE;
        }

        List<TraceTree> traceTrees = traceQueryService.getTraces(limit, mode).stream()
                .map(traceTreeMapper::map)
                .map(issueDetector::detectIssues)
                .toList();

        TraceSummary summary = calculateSummary(traceTrees);
        return new TraceInsightsResponse(traceTrees, summary);
    }

    public Optional<TraceTree> getTraceInsights(String traceId) {
        if (traceQueryService == null) {
            return Optional.empty();
        }

        return traceQueryService.getTrace(traceId)
                .map(traceData -> {
                    List<QueryInfo> queries = queryExtractor.extract(traceData);
                    TraceTree tree = traceTreeMapper.map(traceData);
                    tree = issueDetector.detectIssues(tree);
                    return enrichWithDetails(tree, traceId, queries);
                });
    }

    private TraceTree enrichWithDetails(TraceTree tree, String traceId, List<QueryInfo> queries) {
        List<TraceLog> logs = List.of();
        if (traceDataStorage != null) {
            logs = traceDataStorage.getTrace(traceId)
                    .map(TraceDataBundle::logs)
                    .orElse(List.of())
                    .stream()
                    .map(e -> new TraceLog(
                            e.spanId(),
                            e.timestamp(),
                            e.level(),
                            e.loggerName(),
                            e.message(),
                            e.threadName()
                    ))
                    .toList();
        }

        if (logs.isEmpty() && queries.isEmpty()) {
            return tree;
        }

        return new TraceTree(
                tree.traceId(),
                tree.startTimeMs(),
                tree.durationMs(),
                tree.status(),
                tree.rootActionType(),
                tree.rootOperation(),
                tree.rootSpan(),
                tree.metrics(),
                tree.inheritedAttributes(),
                tree.request(),
                logs.isEmpty() ? null : logs,
                queries.isEmpty() ? null : queries
        );
    }

    private TraceSummary calculateSummary(List<TraceTree> traces) {
        if (traces.isEmpty()) {
            return new TraceSummary(0, 0, 0, 0.0);
        }

        int totalTraces = traces.size();
        int errorCount = 0;
        int slowCount = 0;
        long totalDurationMs = 0;

        for (TraceTree trace : traces) {
            totalDurationMs += trace.durationMs();

            if (trace.status() == TraceStatus.HAS_ERRORS) {
                errorCount++;
            }

            if (trace.status() == TraceStatus.HAS_SLOW_SPANS || hasSlowIssues(trace.rootSpan())) {
                slowCount++;
            }
        }

        double avgDurationMs = (double) totalDurationMs / totalTraces;
        return new TraceSummary(totalTraces, errorCount, slowCount, avgDurationMs);
    }

    private boolean hasSlowIssues(SpanNode span) {
        if (span == null) {
            return false;
        }

        boolean hasSlowIssue = span.issues().stream()
                .anyMatch(issue -> issue.type() == IssueType.SLOW || issue.type() == IssueType.VERY_SLOW);

        if (hasSlowIssue) {
            return true;
        }

        return span.children().stream().anyMatch(this::hasSlowIssues);
    }
}
