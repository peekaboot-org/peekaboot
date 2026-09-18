package org.peekaboot.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

public class TraceInsightsService {

    private static final TraceInsightsResponse EMPTY_RESPONSE =
            new TraceInsightsResponse(List.of(), BucketCounts.empty(), null);

    /**
     * The types a request that names none asks for. A connection-pool trace is routine
     * maintenance - a HikariCP refill or keepalive acquiring a connection outside any
     * traced work - and enough of them arrive to drown everything else, so they are kept
     * in the store but out of the default view.
     */
    private static final Set<RootActionType> DEFAULT_VIEW_TYPES =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(RootActionType.CONNECTION_POOL)));

    /** The one {@code rootActionType} value that asks for every type, the hidden ones included. */
    private static final String EVERY_TYPE = "*";

    @Nullable
    private final TraceStore traceStore;

    private final TraceTreeMapper traceTreeMapper;
    private final IssueDetector issueDetector;
    private final QueryExtractor queryExtractor;
    private final List<String> exclusions;
    private final List<String> applicationPackages;

    /**
     * {@code exclusions} and {@code applicationPackages} are kept in the same order
     * {@link StackTraceFolding#fold} takes them, so a reader can't swap two adjacent
     * same-typed parameters and have it silently compile as marking framework frames
     * application code.
     */
    public TraceInsightsService(
            @Nullable TraceStore traceStore,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor,
            List<String> exclusions,
            List<String> applicationPackages) {
        this.traceStore = traceStore;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
        this.exclusions = List.copyOf(exclusions);
        this.applicationPackages = List.copyOf(applicationPackages);
    }

    /**
     * Tracing is wired only when peekaboot.tracing.enabled is true; without it the store
     * stays absent and every trace endpoint reports empty.
     */
    public boolean isTracingAvailable() {
        return traceStore != null;
    }

    /**
     * The trace listing for one bucket. {@code rootActionType} is a comma-separated
     * include-list of {@link RootActionType} names; naming none asks for
     * {@link #DEFAULT_VIEW_TYPES} and {@code *} asks for every type. {@code rootOperation}
     * matches the root span's name partially and case-insensitively.
     */
    public TraceInsightsResponse getInsights(
            int limit, TraceBucket bucket, String rootActionType, String rootOperation) {
        if (traceStore == null) {
            return EMPTY_RESPONSE;
        }

        Set<RootActionType> actionTypeFilter = parseRootActionTypes(rootActionType);
        final String operationFilter = rootOperation != null && !rootOperation.isBlank() ? rootOperation : null;

        // filtered on each row's root span alone; only the returned page is mapped (and masked)
        List<RowSource> matches =
                RowSources.matching(traceStore, traceTreeMapper, bucket, actionTypeFilter, operationFilter);
        List<TraceTree> traceTrees = matches.stream()
                .limit(limit)
                .map(this::mapRow)
                .map(issueDetector::detectIssues)
                .toList();

        BucketCounts bucketCounts = new BucketCounts(
                traceStore.getTraceCount(TraceBucket.ALL),
                traceStore.getTraceCount(TraceBucket.ERRORS),
                traceStore.getTraceCount(TraceBucket.SLOW));

        BucketCounts filteredBucketCounts = null;
        if (!actionTypeFilter.isEmpty() || operationFilter != null) {
            // the requested bucket's rows are already in hand; the other two need a pass each
            filteredBucketCounts = new BucketCounts(
                    countMatchingTraces(TraceBucket.ALL, bucket, matches, actionTypeFilter, operationFilter),
                    countMatchingTraces(TraceBucket.ERRORS, bucket, matches, actionTypeFilter, operationFilter),
                    countMatchingTraces(TraceBucket.SLOW, bucket, matches, actionTypeFilter, operationFilter));
        }

        return new TraceInsightsResponse(traceTrees, bucketCounts, filteredBucketCounts);
    }

    /**
     * Parses a comma-separated list of {@link RootActionType} names, silently dropping
     * invalid values. A request that names no recognizable type asks for
     * {@link #DEFAULT_VIEW_TYPES}; {@link #EVERY_TYPE} asks for every type, which is the
     * empty set here since an empty set filters nothing away.
     */
    private Set<RootActionType> parseRootActionTypes(String rootActionType) {
        if (rootActionType == null || rootActionType.isBlank()) {
            return DEFAULT_VIEW_TYPES;
        }
        if (EVERY_TYPE.equals(rootActionType.trim())) {
            return Set.of();
        }
        Set<RootActionType> types = EnumSet.noneOf(RootActionType.class);
        for (String token : rootActionType.split(",", -1)) {
            try {
                types.add(RootActionType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // a name no RootActionType carries asks for nothing
            }
        }
        return types.isEmpty() ? DEFAULT_VIEW_TYPES : types;
    }

    private TraceTree mapRow(RowSource row) {
        TraceData traceData = row.bundle().snapshot();
        TraceTree tree = row.subtreeRootSpanId() == null
                ? traceTreeMapper.map(traceData)
                : traceTreeMapper.mapSubtree(traceData, row.subtreeRootSpanId());
        return withLogsSummary(tree, row.bundle().logs());
    }

    /** The list's log badges: counted from the logs the bundle already carries, so no extra lookup. */
    private static TraceTree withLogsSummary(TraceTree tree, List<LogCapturedEvent> logs) {
        return tree.withSummary(withLogs(tree.summary(), logs));
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
     * The bucket's badge count for an active filter: distinct traces, not rows, so it stays
     * comparable to the unfiltered count beside it - a trace with two matching async rows
     * must not outweigh the one trace it is.
     */
    private int countMatchingTraces(
            TraceBucket bucket,
            TraceBucket requested,
            List<RowSource> requestedMatches,
            Set<RootActionType> actionTypes,
            String rootOperation) {
        List<RowSource> rows = bucket == requested
                ? requestedMatches
                : RowSources.matching(traceStore, traceTreeMapper, bucket, actionTypes, rootOperation);
        return (int) rows.stream().map(row -> row.bundle().traceId()).distinct().count();
    }

    public Optional<TraceTree> getTraceInsights(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId).map(bundle -> {
            TraceData traceData = bundle.snapshot();
            List<QueryInfo> queries = queryExtractor.extract(traceData);
            TraceTree tree = traceTreeMapper.map(traceData);
            tree = issueDetector.detectIssues(tree);
            return enrichWithDetails(tree, bundle, queries);
        });
    }

    private TraceTree enrichWithDetails(TraceTree tree, TraceDataBundle bundle, List<QueryInfo> queries) {
        List<LogCapturedEvent> capturedLogs = bundle.logs();
        List<TraceLog> logs =
                capturedLogs.stream().map(e -> toTraceLog(bundle, e)).toList();

        RequestCompletedEvent reqEvent = bundle.request();
        HttpExchange httpExchange = reqEvent != null ? HttpExchange.from(reqEvent) : null;

        return tree.withRootSpan(attachLogsToSpan(tree.rootSpan(), groupLogsBySpan(logs)), tree.slow())
                .withSummary(withLogs(tree.summary(), capturedLogs))
                .withDetails(httpExchange, logs, queries);
    }

    /**
     * Folding happens here rather than at capture: the full trace stays on the event, and these
     * ranges are only the rendering hint the browser needs so it classifies nothing itself.
     */
    private TraceLog toTraceLog(TraceDataBundle bundle, LogCapturedEvent e) {
        if (e.stackTrace() == null) {
            return new TraceLog(
                    bundle.resolveSpanId(e.spanId()),
                    e.timestamp(),
                    e.level(),
                    e.loggerName(),
                    e.message(),
                    e.threadName(),
                    null,
                    List.of(),
                    List.of());
        }
        FoldedTrace folded = StackTraceFolding.fold(e.stackTrace(), exclusions, applicationPackages);
        // The ranges index into folded.lines(), not the raw captured string: String.lines() splits
        // on a lone \r where the browser's split('\n') would not, so sending anything else back
        // would leave the two disagreeing about which line a given index names.
        String wireStackTrace = String.join("\n", folded.lines());
        return new TraceLog(
                bundle.resolveSpanId(e.spanId()),
                e.timestamp(),
                e.level(),
                e.loggerName(),
                e.message(),
                e.threadName(),
                wireStackTrace,
                folded.hidden(),
                folded.applicationFrames());
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

    /**
     * The span tree's own copies carry no stack trace: the frontend's spans tab only ever
     * counts a span's logs, never reads their content, so shipping the trace here a second
     * time - once here, once in the flat details list - would double it on the wire for
     * nothing.
     */
    private SpanNode attachLogsToSpan(SpanNode span, Map<String, List<TraceLog>> logsBySpan) {
        if (span == null) {
            return null;
        }
        List<SpanNode> children = span.children().stream()
                .map(child -> attachLogsToSpan(child, logsBySpan))
                .toList();
        List<TraceLog> spanLogs = logsBySpan.get(span.spanId());
        SpanNode withChildren = span.withChildren(children);
        return spanLogs == null
                ? withChildren
                : withChildren.withLogs(
                        spanLogs.stream().map(TraceLog::withoutStackTrace).toList());
    }
}
