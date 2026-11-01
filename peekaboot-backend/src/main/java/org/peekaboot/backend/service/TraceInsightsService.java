package org.peekaboot.backend.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;

public class TraceInsightsService {

    private static final TraceInsightsResponse EMPTY_RESPONSE =
            new TraceInsightsResponse(List.of(), BucketCounts.empty(), null);

    /**
     * The types a request that names none asks for. A maintenance trace is upkeep no traced
     * work caused, such as a HikariCP refill acquiring a connection or a Redis client's
     * handshake and health check. Enough of
     * them arrive to drown everything else, so they are kept in the store but out of the
     * default view.
     */
    private static final Set<RootActionType> DEFAULT_VIEW_TYPES =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(RootActionType.MAINTENANCE)));

    /** The one {@code rootActionType} value that asks for every type, the hidden ones included. */
    private static final String EVERY_TYPE = "*";

    /** How a refusal lists {@link #EVERY_TYPE}, which a list of types refuses as one of its tokens. */
    private static final String EVERY_TYPE_ALONE = EVERY_TYPE + " on its own";

    @Nullable
    private final TraceStore traceStore;

    private final TraceResources traceResources;

    public TraceInsightsService(
            @Nullable TraceStore traceStore,
            MaskingEngine maskingEngine,
            SqlFormatter sqlFormatter,
            PeekabootTracingProperties tracingProperties,
            StackTraceRules stackTraceRules) {
        this.traceStore = traceStore;
        this.traceResources = new TraceResources(maskingEngine, sqlFormatter, tracingProperties, stackTraceRules);
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
        Set<RootActionType> actionTypeFilter = parseRootActionTypes(rootActionType);
        if (traceStore == null) {
            return EMPTY_RESPONSE;
        }

        final String operationFilter = rootOperation != null && !rootOperation.isBlank() ? rootOperation : null;

        // filtered on each row's root span alone; only the returned page is mapped (and masked)
        List<RowSource> matches = RowSources.matching(traceStore, bucket, actionTypeFilter, operationFilter);
        List<TraceTree> traceTrees = matches.stream()
                .limit(limit)
                .map(row -> traceResources.row(
                        row.bundle(),
                        row.subtreeRootSpanId(),
                        traceStore.bucketsOf(row.bundle().traceId())))
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
     * Parses a comma-separated list of {@link RootActionType} names, refusing a name it does
     * not know. A request that names no type asks for {@link #DEFAULT_VIEW_TYPES};
     * {@link #EVERY_TYPE} asks for every type, which is the empty set here since an empty set
     * filters nothing away.
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
            types.add(RequestParameters.enumValue("rootActionType", RootActionType.class, token, EVERY_TYPE_ALONE));
        }
        return types;
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

        return traceStore.getTrace(traceId).map(bundle -> traceResources.trace(bundle, traceStore.bucketsOf(traceId)));
    }
}
