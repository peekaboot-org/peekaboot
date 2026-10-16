package org.peekaboot.backend.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.stacktrace.StackTraceRules;
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
    private final TraceLogMapper traceLogMapper;

    public TraceInsightsService(
            @Nullable TraceStore traceStore,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor,
            StackTraceRules stackTraceRules) {
        this.traceStore = traceStore;
        this.traceTreeMapper = traceTreeMapper;
        this.issueDetector = issueDetector;
        this.queryExtractor = queryExtractor;
        this.traceLogMapper = new TraceLogMapper(stackTraceRules);
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
        List<RowSource> matches = RowSources.matching(traceStore, bucket, actionTypeFilter, operationFilter);
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
        if (!row.subtree()) {
            return traceLogMapper.withLogsSummary(
                    traceTreeMapper.map(traceData), row.bundle().logs());
        }
        TraceTree subtree = traceTreeMapper.mapSubtree(traceData, row.rootSpan().spanId());
        return traceLogMapper.withLogsSummary(subtree, traceLogMapper.logsWithin(subtree.rootSpan(), row.bundle()));
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
                : RowSources.matching(traceStore, bucket, actionTypes, rootOperation);
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
        RequestCompletedEvent reqEvent = bundle.request();
        HttpExchange httpExchange = reqEvent != null ? HttpExchange.from(reqEvent) : null;
        // an @Async task may log meanwhile, so the badges and the list count one read
        List<LogCapturedEvent> logs = bundle.logs();

        return traceLogMapper
                .withLogsSummary(tree, logs)
                .withDetails(httpExchange, traceLogMapper.map(bundle, logs), queries);
    }
}
