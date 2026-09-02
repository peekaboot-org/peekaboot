package org.peekaboot.backend.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.lang.Nullable;

public class TraceInsightsService {

    private static final TraceInsightsResponse EMPTY_RESPONSE =
            new TraceInsightsResponse(List.of(), BucketCounts.empty(), null);

    @Nullable
    private final TraceStore traceStore;

    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final QueryExtractor queryExtractor;

    public TraceInsightsService(
            @Nullable TraceStore traceStore,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor) {
        this.traceStore = traceStore;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
    }

    /**
     * Tracing is wired only when peekaboot.tracing.enabled is true; without it the store
     * stays absent and every trace endpoint reports empty.
     */
    public boolean isTracingAvailable() {
        return traceStore != null;
    }

    public TraceInsightsResponse getInsights(
            int limit, TraceBucket bucket, String rootActionType, String rootOperation) {
        if (traceStore == null) {
            return EMPTY_RESPONSE;
        }

        Set<RootActionType> actionTypeFilter = parseRootActionTypes(rootActionType);
        final String operationFilter = rootOperation != null && !rootOperation.isBlank() ? rootOperation : null;

        // filtered on the root span alone; only the returned page is mapped (and masked)
        List<TraceDataBundle> matches = matchingBundles(bucket, actionTypeFilter, operationFilter);
        List<TraceTree> traceTrees = matches.stream()
                .limit(limit)
                .map(this::mapBundle)
                .map(issueDetector::detectIssues)
                .toList();

        BucketCounts bucketCounts = new BucketCounts(
                traceStore.getTraceCount(TraceBucket.ALL),
                traceStore.getTraceCount(TraceBucket.ERRORS),
                traceStore.getTraceCount(TraceBucket.SLOW));

        BucketCounts filteredBucketCounts = null;
        if (!actionTypeFilter.isEmpty() || operationFilter != null) {
            // the requested bucket's matches are already in hand; the other two need a pass each
            filteredBucketCounts = new BucketCounts(
                    countMatching(TraceBucket.ALL, bucket, matches, actionTypeFilter, operationFilter),
                    countMatching(TraceBucket.ERRORS, bucket, matches, actionTypeFilter, operationFilter),
                    countMatching(TraceBucket.SLOW, bucket, matches, actionTypeFilter, operationFilter));
        }

        return new TraceInsightsResponse(traceTrees, bucketCounts, filteredBucketCounts);
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
            } catch (IllegalArgumentException ignored) {
            }
        }
        return types;
    }

    /** The bucket's bundles that pass the root-level filters, newest first - nothing mapped or masked yet. */
    private List<TraceDataBundle> matchingBundles(
            TraceBucket bucket, Set<RootActionType> actionTypes, String rootOperation) {
        if (traceStore == null) {
            return List.of();
        }
        return traceStore.getTraces(bucket, Integer.MAX_VALUE).stream()
                .filter(bundle -> matchesFilters(bundle, actionTypes, rootOperation))
                .toList();
    }

    private TraceTree mapBundle(TraceDataBundle bundle) {
        TraceData traceData = TraceData.fromSpans(bundle.traceId(), bundle.spans());
        return withLogsSummary(traceTreeMapper.map(traceData, bundle.truncated()), bundle.logs());
    }

    /** The list's log badges: counted from the logs the bundle already carries, so no extra lookup. */
    private static TraceTree withLogsSummary(TraceTree tree, List<LogCapturedEvent> logs) {
        return new TraceTree(
                tree.traceId(),
                tree.startTimeMs(),
                tree.durationMs(),
                tree.status(),
                tree.slow(),
                tree.rootActionType(),
                tree.rootOperation(),
                tree.rootSpan(),
                withLogs(tree.summary(), logs),
                tree.httpExchange(),
                tree.logs(),
                tree.queries(),
                tree.truncated());
    }

    private static TraceTabSummary withLogs(TraceTabSummary summary, List<LogCapturedEvent> logs) {
        return new TraceTabSummary(summary.request(), summary.spans(), summary.queries(), logsSummary(logs));
    }

    private static TraceTabSummary.LogsSummary logsSummary(List<LogCapturedEvent> logs) {
        int errors = (int) logs.stream().filter(LogCapturedEvent::isError).count();
        int warnings = (int) logs.stream().filter(LogCapturedEvent::isWarn).count();
        return new TraceTabSummary.LogsSummary(logs.size(), errors, warnings);
    }

    /**
     * Classifies a bundle for filtering from its root span alone: action type and
     * operation come straight off {@link TraceTreeMapper}'s root-span logic, so the
     * verdict matches what mapping the full tree would say - without copying, building or
     * masking a tree for bundles the response will never carry.
     */
    private boolean matchesFilters(TraceDataBundle bundle, Set<RootActionType> actionTypes, String rootOperation) {
        if (actionTypes.isEmpty() && rootOperation == null) {
            return true;
        }
        SpanData root = bundle.rootSpan();
        return (actionTypes.isEmpty() || actionTypes.contains(traceTreeMapper.detectRootActionType(root)))
                && (rootOperation == null || matchesRootOperation(root != null ? root.name() : null, rootOperation));
    }

    private int countMatching(
            TraceBucket bucket,
            TraceBucket requested,
            List<TraceDataBundle> requestedMatches,
            Set<RootActionType> actionTypes,
            String rootOperation) {
        return bucket == requested
                ? requestedMatches.size()
                : matchingBundles(bucket, actionTypes, rootOperation).size();
    }

    /** The chip sends a substring of the operation name, so a partial, case-insensitive match is the contract. */
    private boolean matchesRootOperation(String rootOperationName, String rootOperation) {
        if (rootOperationName == null) {
            return false;
        }
        String operation = rootOperationName.toLowerCase(Locale.ROOT);
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

        return traceStore.getTrace(traceId).map(bundle -> {
            TraceData traceData = TraceData.fromSpans(bundle.traceId(), bundle.spans());
            List<QueryInfo> queries = queryExtractor.extract(traceData);
            TraceTree tree = traceTreeMapper.map(traceData, bundle.truncated());
            tree = issueDetector.detectIssues(tree);
            return enrichWithDetails(tree, bundle, queries);
        });
    }

    private TraceTree enrichWithDetails(TraceTree tree, TraceDataBundle bundle, List<QueryInfo> queries) {
        List<LogCapturedEvent> capturedLogs = bundle.logs();
        List<TraceLog> logs = capturedLogs.stream()
                .map(e -> new TraceLog(
                        bundle.resolveSpanId(e.spanId()),
                        e.timestamp(),
                        e.level(),
                        e.loggerName(),
                        e.message(),
                        e.threadName()))
                .toList();

        RequestCompletedEvent reqEvent = bundle.request();
        HttpExchange httpExchange = reqEvent != null ? HttpExchange.from(reqEvent) : null;

        return new TraceTree(
                tree.traceId(),
                tree.startTimeMs(),
                tree.durationMs(),
                tree.status(),
                tree.slow(),
                tree.rootActionType(),
                tree.rootOperation(),
                attachLogsToSpan(tree.rootSpan(), groupLogsBySpan(logs)),
                withLogs(tree.summary(), capturedLogs),
                httpExchange,
                logs,
                queries,
                tree.truncated());
    }

    /** Logs by the span they were emitted in; a log with no span id belongs to the flat list only. */
    private static Map<String, List<TraceLog>> groupLogsBySpan(List<TraceLog> logs) {
        Map<String, List<TraceLog>> logsBySpan = new HashMap<>();
        for (TraceLog log : logs) {
            if (log.spanId() != null) {
                logsBySpan.computeIfAbsent(log.spanId(), k -> new ArrayList<>()).add(log);
            }
        }
        return logsBySpan;
    }

    private SpanNode attachLogsToSpan(SpanNode span, Map<String, List<TraceLog>> logsBySpan) {
        if (span == null) {
            return null;
        }

        List<TraceLog> spanLogs = logsBySpan.get(span.spanId());

        List<SpanNode> enrichedChildren = null;
        if (span.children() != null && !span.children().isEmpty()) {
            enrichedChildren = span.children().stream()
                    .map(child -> attachLogsToSpan(child, logsBySpan))
                    .toList();
        }

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
}
