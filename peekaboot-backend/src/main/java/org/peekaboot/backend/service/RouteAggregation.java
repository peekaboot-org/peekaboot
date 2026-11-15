package org.peekaboot.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.RouteListResponse;
import org.peekaboot.backend.domain.trace.RouteStats;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.mapper.trace.RootActionTypes;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.tracing.store.ListedTrace;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Folds the All bucket's complete HTTP requests, newest first, into one row per method and route.
 * Each is counted from its listing facts, which say what its summary says, so no summary is mapped.
 * Every duration is kept until the end, so the percentiles are nearest rank over every request, the
 * way {@link AggregateStats} computes the Insights ones.
 */
final class RouteAggregation {

    private static final Comparator<RouteStats> BUSIEST_FIRST = Comparator.comparingInt(RouteStats::count)
            .reversed()
            .thenComparing(RouteStats::method, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RouteStats::route, Comparator.nullsLast(Comparator.naturalOrder()));

    private final Map<RouteKey, RouteTotals> totals = new LinkedHashMap<>();

    @Nullable
    private Instant from;

    @Nullable
    private Instant to;

    private int traceCount;

    private RouteAggregation() {}

    /**
     * Reads the All bucket's entries under its lock, then per bundle its listing facts under one
     * lock and, for a request a route counts, its spans under one more for the issue types. No log
     * and no bucket is read. A trace still in flight at the facts read has no root yet, so it waits
     * for the next call. What changes once a trace is complete, such as a late ERROR log or a
     * failing async task, counts as the facts hold it. A span that lands between the two reads can
     * raise an issue type the facts do not explain yet. The next call agrees.
     */
    static RouteListResponse over(
            @Nullable TraceStore traceStore, TraceResources traceResources, long slowTraceThresholdMs) {
        RouteAggregation aggregation = new RouteAggregation();
        if (traceStore == null) {
            return aggregation.response();
        }
        for (ListedTrace listed : traceStore.getTraces(TraceBucket.ALL, Integer.MAX_VALUE)) {
            TraceDataBundle bundle = listed.bundle();
            ListingRoots roots = bundle.listingRoots();
            TraceSummary.Request request = requestOf(roots, traceResources);
            if (request != null) {
                aggregation.add(new CountedRequest(
                        bundle.traceId(),
                        new RouteKey(request.method(), request.route()),
                        roots.startTime(),
                        roots.durationMs(),
                        TraceFilter.outcomeOf(roots) == TraceOutcome.ERROR,
                        roots.durationMs() >= slowTraceThresholdMs,
                        traceResources.issueTypes(bundle)));
            }
        }
        return aggregation.response();
    }

    /**
     * Null for a trace no route counts. A server root that carries no HTTP tag classifies as an HTTP
     * request but names none.
     */
    private static TraceSummary.@Nullable Request requestOf(ListingRoots roots, TraceResources traceResources) {
        return RootActionTypes.of(roots.root()) == RootActionType.HTTP_REQUEST
                ? traceResources.requestOf(roots.root())
                : null;
    }

    /** {@code request} is older than every request added before it. */
    private void add(CountedRequest request) {
        totals.computeIfAbsent(request.route(), key -> new RouteTotals()).add(request);
        Instant start = request.startTime();
        if (start != null) {
            from = from == null || start.isBefore(from) ? start : from;
            to = to == null || start.isAfter(to) ? start : to;
        }
        traceCount++;
    }

    private RouteListResponse response() {
        List<RouteStats> routes = totals.entrySet().stream()
                .map(entry -> entry.getValue().stats(entry.getKey()))
                .sorted(BUSIEST_FIRST)
                .toList();
        return new RouteListResponse(new RouteListResponse.Window(from, to, traceCount), routes);
    }

    private record RouteKey(
            @Nullable String method, @Nullable String route) {}

    /**
     * What a route counts of one request, as its summary says it.
     *
     * @param failed its outcome is {@code ERROR}
     * @param slow it ran at or above the slow-trace threshold, the rule of the {@code SLOW} reason
     * @param issueTypes the type of each issue it lists, once
     */
    private record CountedRequest(
            String traceId,
            RouteKey route,
            @Nullable Instant startTime,
            long durationMs,
            boolean failed,
            boolean slow,
            Set<IssueType> issueTypes) {}

    private static final class RouteTotals {

        private final List<Long> durationsMs = new ArrayList<>();
        private final Map<IssueType, Integer> issueCounts = new EnumMap<>(IssueType.class);
        private int errorCount;

        @Nullable
        private String latestErrorTraceId;

        @Nullable
        private String latestSlowTraceId;

        RouteTotals() {
            for (IssueType type : IssueType.values()) {
                issueCounts.put(type, 0);
            }
        }

        /** Requests arrive newest first, so the first failed and the first slow one are the latest. */
        void add(CountedRequest request) {
            durationsMs.add(request.durationMs());
            if (request.failed()) {
                errorCount++;
                if (latestErrorTraceId == null) {
                    latestErrorTraceId = request.traceId();
                }
            }
            if (latestSlowTraceId == null && request.slow()) {
                latestSlowTraceId = request.traceId();
            }
            request.issueTypes().forEach(type -> issueCounts.merge(type, 1, Integer::sum));
        }

        RouteStats stats(RouteKey key) {
            AggregateStats durations = AggregateStats.of(
                    durationsMs.stream().mapToDouble(Long::doubleValue).toArray());
            return new RouteStats(
                    key.method(),
                    key.route(),
                    durationsMs.size(),
                    errorCount,
                    Math.round(durations.median()),
                    Math.round(durations.p95()),
                    Math.round(durations.max()),
                    latestErrorTraceId,
                    latestSlowTraceId,
                    Collections.unmodifiableMap(new EnumMap<>(issueCounts)));
        }
    }
}
