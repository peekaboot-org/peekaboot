package org.peekaboot.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.RouteListResponse;
import org.peekaboot.backend.domain.trace.RouteStats;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.mapper.trace.RootActionTypes;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.tracing.store.ListedTrace;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Folds the summaries of the All bucket's complete HTTP requests, newest first, into one row per
 * method and route. Every duration is kept until the end, so the percentiles are nearest rank over
 * every request, the way {@link AggregateStats} computes the Insights ones.
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
     * lock, then its summary through {@link TraceResources#row}, which reads the bundle's spans and
     * its logs one lock each, with the bucket membership read one bucket at a time. The facts skip
     * what no route counts before anything is mapped. A trace still in flight at the facts read has
     * no root yet, so it waits for the next call. What changes once a trace is complete, such as a
     * late ERROR log or a failing async task, counts as the summary reads it.
     */
    static RouteListResponse over(@Nullable TraceStore traceStore, TraceResources traceResources) {
        RouteAggregation aggregation = new RouteAggregation();
        if (traceStore == null) {
            return aggregation.response();
        }
        for (ListedTrace listed : traceStore.getTraces(TraceBucket.ALL, Integer.MAX_VALUE)) {
            ListingRoots roots = listed.bundle().listingRoots();
            if (RootActionTypes.of(roots.root()) == RootActionType.HTTP_REQUEST) {
                String traceId = listed.bundle().traceId();
                aggregation.add(traceResources
                        .row(listed.bundle(), null, traceStore.bucketsOf(traceId), TraceView.SUMMARY)
                        .summary());
            }
        }
        return aggregation.response();
    }

    /**
     * {@code summary} is older than every summary added before it. A server root that carries no
     * HTTP tag classifies as an HTTP request but names none, so no route counts it.
     */
    private void add(TraceSummary summary) {
        TraceSummary.Request request = summary.request();
        if (request == null) {
            return;
        }
        totals.computeIfAbsent(new RouteKey(request.method(), request.route()), key -> new RouteTotals())
                .add(summary);
        Instant start = summary.startTime();
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

        /** Summaries arrive newest first, so the first failed and the first slow one are the latest. */
        void add(TraceSummary summary) {
            durationsMs.add(summary.durationMs());
            if (summary.outcome() == TraceOutcome.ERROR) {
                errorCount++;
                if (latestErrorTraceId == null) {
                    latestErrorTraceId = summary.traceId();
                }
            }
            if (latestSlowTraceId == null && isSlow(summary)) {
                latestSlowTraceId = summary.traceId();
            }
            summary.issues().stream()
                    .map(TraceIssue::type)
                    .distinct()
                    .forEach(type -> issueCounts.merge(type, 1, Integer::sum));
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

        private static boolean isSlow(TraceSummary summary) {
            return summary.reasons().stream().anyMatch(reason -> reason.kind() == TraceReason.Kind.SLOW);
        }
    }
}
