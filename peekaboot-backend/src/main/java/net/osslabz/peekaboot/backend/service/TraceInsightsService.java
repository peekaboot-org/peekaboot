package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.domain.trace.HttpExchange;
import net.osslabz.peekaboot.backend.domain.trace.HttpRequest;
import net.osslabz.peekaboot.backend.domain.trace.HttpResponse;
import net.osslabz.peekaboot.backend.domain.trace.IssueType;
import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.backend.domain.trace.RootActionType;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceListSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceLog;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTabSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.mapper.trace.IssueDetector;
import net.osslabz.peekaboot.backend.mapper.trace.QueryExtractor;
import net.osslabz.peekaboot.backend.mapper.trace.SpanDeduplicator;
import net.osslabz.peekaboot.backend.mapper.trace.TraceTreeMapper;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataBundle;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TraceInsightsService {

    private static final TraceInsightsResponse EMPTY_RESPONSE = new TraceInsightsResponse(
            List.of(),
            new TraceListSummary(0, 0, 0, 0.0)
    );

    @Nullable
    private final TraceQueryService traceQueryService;
    @Nullable
    private final TraceDataStorage traceDataStorage;
    private final SpanDeduplicator spanDeduplicator;
    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final QueryExtractor queryExtractor;

    public TraceInsightsService(
            @Nullable TraceQueryService traceQueryService,
            @Nullable TraceDataStorage traceDataStorage,
            SpanDeduplicator spanDeduplicator,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor) {
        this.traceQueryService = traceQueryService;
        this.traceDataStorage = traceDataStorage;
        this.spanDeduplicator = spanDeduplicator;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
    }

    public TraceInsightsResponse getInsights(int limit, TraceCaptureMode mode) {
        return getInsights(limit, mode, null, null);
    }

    public TraceInsightsResponse getInsights(int limit, TraceCaptureMode mode, String rootActionType, String rootOperation) {
        if (traceQueryService == null) {
            return EMPTY_RESPONSE;
        }

        // Parse rootActionType filter
        RootActionType actionTypeFilter = null;
        if (rootActionType != null && !rootActionType.isBlank()) {
            try {
                actionTypeFilter = RootActionType.valueOf(rootActionType.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore invalid values, don't filter
            }
        }

        final RootActionType finalActionTypeFilter = actionTypeFilter;
        final String finalRootOperation = rootOperation != null && !rootOperation.isBlank() ? rootOperation : null;

        List<TraceTree> traceTrees = traceQueryService.getTraces(limit * 10, mode).stream()  // Fetch more to account for filtering
                .map(spanDeduplicator::deduplicate)
                .map(traceTreeMapper::map)
                .filter(tree -> finalActionTypeFilter == null || tree.rootActionType() == finalActionTypeFilter)
                .filter(tree -> finalRootOperation == null || matchesRootOperation(tree, finalRootOperation))
                .map(issueDetector::detectIssues)
                .limit(limit)
                .toList();

        TraceListSummary summary = calculateListSummary(traceTrees);
        return new TraceInsightsResponse(traceTrees, summary);
    }

    private boolean matchesRootOperation(TraceTree tree, String rootOperation) {
        if (tree.rootOperation() == null) {
            return false;
        }
        // Support partial matching for flexibility
        return tree.rootOperation().toLowerCase().contains(rootOperation.toLowerCase());
    }

    public Optional<TraceTree> getTraceInsights(String traceId) {
        if (traceQueryService == null) {
            return Optional.empty();
        }

        return traceQueryService.getTrace(traceId)
                .map(spanDeduplicator::deduplicate)
                .map(traceData -> {
                    List<QueryInfo> queries = queryExtractor.extract(traceData);
                    TraceTree tree = traceTreeMapper.map(traceData);
                    tree = issueDetector.detectIssues(tree);
                    return enrichWithDetails(tree, traceId, queries);
                });
    }

    private TraceTree enrichWithDetails(TraceTree tree, String traceId, List<QueryInfo> queries) {
        List<TraceLog> logs = List.of();
        HttpExchange httpExchange = null;

        if (traceDataStorage != null) {
            Optional<TraceDataBundle> bundleOpt = traceDataStorage.getTrace(traceId);

            if (bundleOpt.isPresent()) {
                TraceDataBundle bundle = bundleOpt.get();

                // Extract logs
                logs = bundle.logs().stream()
                        .map(e -> new TraceLog(
                                e.spanId(),
                                e.timestamp(),
                                e.level(),
                                e.loggerName(),
                                e.message(),
                                e.threadName()
                        ))
                        .toList();

                // Extract HTTP exchange details
                RequestCompletedEvent reqEvent = bundle.request();
                if (reqEvent != null) {
                    httpExchange = HttpExchange.from(reqEvent);
                }
            }
        }

        if (logs.isEmpty() && queries.isEmpty() && httpExchange == null) {
            return tree;
        }

        // Attach logs to their respective spans
        SpanNode enrichedRootSpan = tree.rootSpan();
        if (!logs.isEmpty() && enrichedRootSpan != null) {
            Map<String, List<TraceLog>> logsBySpan = groupLogsBySpan(logs);
            enrichedRootSpan = attachLogsToSpan(enrichedRootSpan, logsBySpan);
        }

        // Update logs summary
        TraceTabSummary updatedSummary = tree.summary();
        if (!logs.isEmpty()) {
            int errorLogCount = (int) logs.stream().filter(l -> "ERROR".equalsIgnoreCase(l.level())).count();
            int warnLogCount = (int) logs.stream().filter(l -> "WARN".equalsIgnoreCase(l.level())).count();
            updatedSummary = new TraceTabSummary(
                    tree.summary().request(),
                    tree.summary().spans(),
                    tree.summary().queries(),
                    new TraceTabSummary.LogsSummary(logs.size(), errorLogCount, warnLogCount)
            );
        }

        return new TraceTree(
                tree.traceId(),
                tree.startTimeMs(),
                tree.durationMs(),
                tree.status(),
                tree.rootActionType(),
                tree.rootOperation(),
                enrichedRootSpan,
                updatedSummary,
                tree.inheritedAttributes(),
                httpExchange,
                logs.isEmpty() ? null : logs,
                queries.isEmpty() ? null : queries
        );
    }

    private Map<String, List<TraceLog>> groupLogsBySpan(List<TraceLog> logs) {
        Map<String, List<TraceLog>> logsBySpan = new HashMap<>();
        for (TraceLog log : logs) {
            String spanId = log.spanId() != null ? log.spanId() : "unknown";
            logsBySpan.computeIfAbsent(spanId, k -> new ArrayList<>()).add(log);
        }
        return logsBySpan;
    }

    private SpanNode attachLogsToSpan(SpanNode span, Map<String, List<TraceLog>> logsBySpan) {
        if (span == null) {
            return null;
        }

        // Get logs for this span
        List<TraceLog> spanLogs = logsBySpan.get(span.spanId());

        // Recursively process children
        List<SpanNode> enrichedChildren = null;
        if (span.children() != null && !span.children().isEmpty()) {
            enrichedChildren = span.children().stream()
                    .map(child -> attachLogsToSpan(child, logsBySpan))
                    .toList();
        }

        // Create new span with logs attached
        if (spanLogs != null || enrichedChildren != null) {
            SpanNode result = span;
            if (spanLogs != null) {
                result = result.withLogs(spanLogs);
            }
            if (enrichedChildren != null) {
                result = result.withChildren(enrichedChildren);
            }
            return result;
        }

        return span;
    }

    private TraceListSummary calculateListSummary(List<TraceTree> traces) {
        if (traces.isEmpty()) {
            return new TraceListSummary(0, 0, 0, 0.0);
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

            if (hasSlowIssues(trace.rootSpan())) {
                slowCount++;
            }
        }

        double avgDurationMs = (double) totalDurationMs / totalTraces;
        return new TraceListSummary(totalTraces, errorCount, slowCount, avgDurationMs);
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
