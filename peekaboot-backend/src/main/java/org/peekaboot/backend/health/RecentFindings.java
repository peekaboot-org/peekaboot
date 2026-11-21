package org.peekaboot.backend.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.insights.InsightsDataReader;
import org.peekaboot.backend.insights.InsightsProblem;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.digest.DigestedError;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.store.ListedTrace;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * What Peekaboot saw lately: the insights problems over level 0's ring, the Errors and Slow traces
 * and the distinct failures from the last {@code window}. A source that is off contributes nothing.
 * Every count is of what started inside the window, so none only ever grows: the buckets' sizes and
 * the digest's counts are left out on purpose.
 */
public final class RecentFindings {

    static final int MAX_FAILURES = 10;

    private final @Nullable InsightsDataReader insights;
    private final @Nullable TraceStore traceStore;
    private final @Nullable ErrorDigest digest;
    private final MaskingEngine maskingEngine;
    private final Duration window;
    private final Clock clock;

    public RecentFindings(
            @Nullable InsightsDataReader insights,
            @Nullable TraceStore traceStore,
            @Nullable ErrorDigest digest,
            MaskingEngine maskingEngine,
            Duration window,
            Clock clock) {
        this.insights = insights;
        this.traceStore = traceStore;
        this.digest = digest;
        this.maskingEngine = maskingEngine;
        this.window = window;
        this.clock = clock;
    }

    /**
     * {@code problems} is empty without insights, {@code traces} null without tracing and
     * {@code failures} empty without the digest. A trace that has no start yet is not counted.
     */
    public Findings read() {
        Instant since = clock.instant().minus(window);
        return new Findings(
                insights == null ? List.of() : insights.currentProblems(),
                window.toMillis(),
                traceStore == null
                        ? null
                        : new HealthReport.Traces(
                                startedSince(traceStore, TraceBucket.ERRORS, since),
                                startedSince(traceStore, TraceBucket.SLOW, since)),
                digest == null ? List.of() : failuresSince(digest, since));
    }

    private static int startedSince(TraceStore store, TraceBucket bucket, Instant since) {
        return (int) store.getTraces(bucket, Integer.MAX_VALUE).stream()
                .map(ListedTrace::bundle)
                .map(bundle -> bundle.listingRoots().startTime())
                .filter(start -> start != null && !start.isBefore(since))
                .count();
    }

    private List<HealthReport.Failure> failuresSince(ErrorDigest source, Instant since) {
        return source.entries().stream()
                .filter(entry -> !entry.lastSeen().isBefore(since))
                .limit(MAX_FAILURES)
                .map(this::failure)
                .toList();
    }

    private HealthReport.Failure failure(DigestedError entry) {
        // a run before the digest masked its messages may have stored one unmasked
        String message = entry.lastMessage();
        return new HealthReport.Failure(
                entry.type(),
                entry.route(),
                entry.applicationFrame(),
                entry.lastSeen(),
                message == null ? null : maskingEngine.maskValue(message),
                entry.lastTraceId());
    }

    public record Findings(
            List<InsightsProblem> problems,
            long windowMs,
            HealthReport.@Nullable Traces traces,
            List<HealthReport.Failure> failures) {}
}
