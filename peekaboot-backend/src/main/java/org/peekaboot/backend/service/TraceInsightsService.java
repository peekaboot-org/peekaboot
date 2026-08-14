package org.peekaboot.backend.service;

import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.HttpRequest;
import org.peekaboot.backend.domain.trace.HttpResponse;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceListSummary;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.SpanDeduplicator;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;
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
            new TraceListSummary(0, 0, 0, 0.0),
            BucketCounts.empty()
    );

    @Nullable
    private final TraceStore traceStore;
    private final SpanDeduplicator spanDeduplicator;
    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final QueryExtractor queryExtractor;

    public TraceInsightsService(
            @Nullable TraceStore traceStore,
            SpanDeduplicator spanDeduplicator,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor) {
        this.traceStore = traceStore;
        this.spanDeduplicator = spanDeduplicator;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
    }

    public TraceInsightsResponse getInsights(int limit, TraceBucket bucket, String rootActionType, String rootOperation) {
        if (traceStore == null) {
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

        List<TraceTree> traceTrees = traceStore.getTraces(bucket, limit * 10).stream()  // overfetch to survive filtering
                .map(bundle -> TraceData.fromSpans(bundle.traceId(), bundle.spans()))
                .map(spanDeduplicator::deduplicate)
                .map(traceTreeMapper::map)
                .filter(tree -> finalActionTypeFilter == null || tree.rootActionType() == finalActionTypeFilter)
                .filter(tree -> finalRootOperation == null || matchesRootOperation(tree, finalRootOperation))
                .map(issueDetector::detectIssues)
                .limit(limit)
                .toList();

        BucketCounts bucketCounts = new BucketCounts(
                traceStore.getTraceCount(TraceBucket.ALL),
                traceStore.getTraceCount(TraceBucket.ERRORS),
                traceStore.getTraceCount(TraceBucket.SLOW));

        return new TraceInsightsResponse(traceTrees, calculateListSummary(traceTrees), bucketCounts);
    }

    private boolean matchesRootOperation(TraceTree tree, String rootOperation) {
        if (tree.rootOperation() == null) {
            return false;
        }
        // Support partial matching for flexibility
        return tree.rootOperation().toLowerCase().contains(rootOperation.toLowerCase());
    }

    public Optional<TraceTree> getTraceInsights(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId)
                .map(bundle -> {
                    TraceData traceData = spanDeduplicator.deduplicate(TraceData.fromSpans(bundle.traceId(), bundle.spans()));
                    List<QueryInfo> queries = queryExtractor.extract(traceData);
                    TraceTree tree = traceTreeMapper.map(traceData);
                    tree = issueDetector.detectIssues(tree);
                    return enrichWithDetails(tree, bundle, queries);
                });
    }

    private TraceTree enrichWithDetails(TraceTree tree, TraceDataBundle bundle, List<QueryInfo> queries) {
        List<TraceLog> logs = bundle.logs().stream()
                .map(e -> new TraceLog(
                        e.spanId(),
                        e.timestamp(),
                        e.level(),
                        e.loggerName(),
                        e.message(),
                        e.threadName()
                ))
                .toList();

        HttpExchange httpExchange = null;
        RequestCompletedEvent reqEvent = bundle.request();
        if (reqEvent != null) {
            httpExchange = HttpExchange.from(reqEvent);
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
