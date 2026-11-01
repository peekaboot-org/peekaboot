package org.peekaboot.backend.service;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;

public class TraceInsightsService {

    private static final TraceListResponse EMPTY_RESPONSE =
            new TraceListResponse(List.of(), BucketCounts.empty(), null);

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
     * One bucket's rows that match every filter the request names, newest first, at most {@code limit}.
     * Every parameter is read and checked before the store is consulted, so a bad one is refused even
     * while tracing is off. What each filter matches is on {@link TraceFilter}. {@code view} names a
     * {@link org.peekaboot.backend.domain.trace.TraceView}, the summary alone where absent;
     * {@code limit} is 1 to 10000, and 100 where absent.
     */
    public TraceListResponse listTraces(TraceListParameters parameters) {
        TraceListQuery query = TraceListQuery.of(parameters);
        if (traceStore == null) {
            return EMPTY_RESPONSE;
        }

        RowResources resources = new RowResources(traceStore, traceResources, query.view());
        TraceFilter filter = query.filter();
        // filtered on each row's listing facts; only the returned page is mapped (and masked), plus
        // the async rows a verdict filter has to read
        List<RowSource> matches = RowSources.matching(traceStore, query.bucket(), filter, resources::summaryOf);
        List<RowSource> page = matches.stream().limit(query.limit()).toList();

        BucketCounts bucketCounts = new BucketCounts(
                traceStore.getTraceCount(TraceBucket.ALL),
                traceStore.getTraceCount(TraceBucket.ERRORS),
                traceStore.getTraceCount(TraceBucket.SLOW));

        BucketCounts filteredBucketCounts = null;
        if (filter.filtersAnything()) {
            // the requested bucket's rows are already in hand; the other two need a pass each
            filteredBucketCounts = new BucketCounts(
                    countMatchingTraces(TraceBucket.ALL, query.bucket(), matches, filter, resources),
                    countMatchingTraces(TraceBucket.ERRORS, query.bucket(), matches, filter, resources),
                    countMatchingTraces(TraceBucket.SLOW, query.bucket(), matches, filter, resources));
        }

        return new TraceListResponse(page.stream().map(resources::of).toList(), bucketCounts, filteredBucketCounts);
    }

    /**
     * The bucket's badge count for an active filter: distinct traces, not rows, so it stays
     * comparable to the unfiltered count beside it - a trace with two matching async rows
     * must not outweigh the one trace it is.
     *
     * <p>A bucket other than the requested one takes a pass of its own, which reads every bundle's
     * listing facts again. A trace that completes or fails between the two passes can be counted
     * under a filter the page did not list it under, or the other way round. An async row's verdict
     * is the one mapping {@code resources} already holds.
     */
    private int countMatchingTraces(
            TraceBucket bucket,
            TraceBucket requested,
            List<RowSource> requestedMatches,
            TraceFilter filter,
            RowResources resources) {
        List<RowSource> rows = bucket == requested
                ? requestedMatches
                : RowSources.matching(traceStore, bucket, filter, resources::summaryOf);
        return (int) rows.stream().map(row -> row.bundle().traceId()).distinct().count();
    }

    public Optional<TraceResource> getTrace(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId).map(bundle -> traceResources.trace(bundle, traceStore.bucketsOf(traceId)));
    }
}
