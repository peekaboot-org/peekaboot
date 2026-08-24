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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.Locale;

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

        Set<RootActionType> actionTypeFilter = parseRootActionTypes(rootActionType);
        final String operationFilter = rootOperation != null && !rootOperation.isBlank() ? rootOperation : null;

        List<TraceTree> traceTrees = mapBucket(bucket, limit * 10)  // overfetch to survive filtering
                .filter(tree -> matchesFilters(tree, actionTypeFilter, operationFilter))
                .map(issueDetector::detectIssues)
                .limit(limit)
                .toList();

        BucketCounts bucketCounts = new BucketCounts(
                traceStore.getTraceCount(TraceBucket.ALL),
                traceStore.getTraceCount(TraceBucket.ERRORS),
                traceStore.getTraceCount(TraceBucket.SLOW));

        BucketCounts filteredBucketCounts = null;
        if (!actionTypeFilter.isEmpty() || operationFilter != null) {
            filteredBucketCounts = new BucketCounts(
                    countMatching(TraceBucket.ALL, actionTypeFilter, operationFilter),
                    countMatching(TraceBucket.ERRORS, actionTypeFilter, operationFilter),
                    countMatching(TraceBucket.SLOW, actionTypeFilter, operationFilter));
        }

        return new TraceInsightsResponse(traceTrees, calculateListSummary(traceTrees), bucketCounts, filteredBucketCounts);
    }

    /**
     * Parses a comma-separated list of {@link RootActionType} names, silently dropping invalid
     * values. An empty result means "no type filter".
     */
    private Set<RootActionType> parseRootActionTypes(String rootActionType) {
        if (rootActionType == null || rootActionType.isBlank()) {
            return Set.of();
        }
        Set<RootActionType> types = EnumSet.noneOf(RootActionType.class);
        for (String token : rootActionType.split(",", -1)) {
            try {
                types.add(RootActionType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // Ignore invalid values
            }
        }
        return types;
    }

    private Stream<TraceTree> mapBucket(TraceBucket bucket, int limit) {
        if (traceStore == null) {
            return Stream.empty();
        }
        return traceStore.getTraces(bucket, limit).stream()
                .map(bundle -> {
                    TraceData traceData = spanDeduplicator.deduplicate(
                            TraceData.fromSpans(bundle.traceId(), bundle.spans()));
                    return traceTreeMapper.map(traceData, bundle.truncated());
                })
                .filter(Objects::nonNull);
    }

    private boolean matchesFilters(TraceTree tree, Set<RootActionType> actionTypes, String rootOperation) {
        return (actionTypes.isEmpty() || actionTypes.contains(tree.rootActionType()))
                && (rootOperation == null || matchesRootOperation(tree, rootOperation));
    }

    private int countMatching(TraceBucket bucket, Set<RootActionType> actionTypes, String rootOperation) {
        return (int) mapBucket(bucket, Integer.MAX_VALUE)
                .filter(tree -> matchesFilters(tree, actionTypes, rootOperation))
                .count();
    }

    private boolean matchesRootOperation(TraceTree tree, String rootOperation) {
        if (tree.rootOperation() == null) {
            return false;
        }
        // Support partial matching for flexibility
        String operation = tree.rootOperation().toLowerCase(Locale.ROOT);
        String filter = rootOperation.toLowerCase(Locale.ROOT);
        if (operation.contains(filter)) {
            return true;
        }
        // Scheduled task targets are fully qualified (package.Class.method) while span
        // names use the bean name ("task class.method") - also match on the Class.method suffix
        String[] segments = filter.split("\\.", -1);
        return segments.length > 2
                && operation.contains(segments[segments.length - 2] + "." + segments[segments.length - 1]);
    }

    public Optional<TraceTree> getTraceInsights(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId)
                .map(bundle -> {
                    TraceData traceData = spanDeduplicator.deduplicate(TraceData.fromSpans(bundle.traceId(), bundle.spans()));
                    List<QueryInfo> queries = queryExtractor.extract(traceData);
                    TraceTree tree = traceTreeMapper.map(traceData, bundle.truncated());
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
                queries.isEmpty() ? null : queries,
                tree.truncated()
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

        return hasSlowIssue || span.children().stream().anyMatch(this::hasSlowIssues);
    }
}
