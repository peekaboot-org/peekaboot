package org.peekaboot.backend.health;

import static io.micrometer.tracing.Span.Kind.SERVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.api.IssueSeverity;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.insights.InsightsProblem;
import org.peekaboot.backend.insights.ProblemRule;
import org.peekaboot.backend.insights.TickedInsights;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.FailedTraces;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.digest.DigestedError;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInput;

/** What the health report says Peekaboot saw lately, over level 0's window. */
class RecentFindingsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final InMemoryTraceStore store = TraceStores.withDefaults();
    private final ErrorDigest digest = new ErrorDigest();

    private RecentFindings findings() {
        return new RecentFindings(null, store, digest, new MaskingEngine(), WINDOW, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void arrive(SpanData span) {
        FailedTraces.recorder(store, digest).liveInputs().accept(new TraceInput.SpanArrived(span));
    }

    private void slowTraceStarting(String id, Instant start) {
        arrive(span("root")
                .in(id)
                .kind(SERVER)
                .at(start, Duration.ofMillis(1500))
                .build());
    }

    private static DigestedError failureLastSeen(String traceId, Instant lastSeen) {
        return new DigestedError(
                FailedTraces.TYPE, "/" + traceId, null, 1, lastSeen, lastSeen, "failed", traceId, List.of());
    }

    @Test
    void countsTheErrorsAndSlowTracesThatStartedInsideTheWindow() {
        arrive(FailedTraces.root("e1", NOW.minusSeconds(60), 500));
        arrive(FailedTraces.root("e2", NOW.minusSeconds(120), 500));
        arrive(FailedTraces.root("e3", NOW.minus(Duration.ofMinutes(16)), 500));
        slowTraceStarting("s1", NOW.minusSeconds(30));
        slowTraceStarting("s2", NOW.minus(Duration.ofMinutes(20)));

        assertThat(findings().read().traces()).isEqualTo(new HealthReport.Traces(2, 1));
    }

    @Test
    void aTraceThatStartedExactlyAtTheWindowsStartCounts() {
        arrive(FailedTraces.root("e1", NOW.minus(WINDOW), 500));

        assertThat(findings().read().traces().errors()).isEqualTo(1);
    }

    @Test
    void listsTheFailuresLastSeenInsideTheWindowLatestFirst() {
        digest.restore(List.of(
                failureLastSeen("older", NOW.minusSeconds(300)),
                failureLastSeen("newest", NOW.minusSeconds(10)),
                failureLastSeen("ancient", NOW.minus(Duration.ofHours(1)))));

        assertThat(findings().read().failures())
                .extracting(HealthReport.Failure::lastTraceId)
                .containsExactly("newest", "older");
    }

    @Test
    void listsAtMostTenFailures() {
        digest.restore(IntStream.range(0, 12)
                .mapToObj(i -> failureLastSeen("f" + i, NOW.minusSeconds(10 + i)))
                .toList());

        assertThat(findings().read().failures())
                .extracting(HealthReport.Failure::lastTraceId)
                .containsExactly("f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9");
    }

    @Test
    void masksAFailureMessageAnEarlierRunStoredUnmasked() {
        Instant t = NOW.minusSeconds(10);
        digest.restore(List.of(new DigestedError(
                FailedTraces.TYPE,
                "/orders",
                null,
                1,
                t,
                t,
                "cannot reach redis://cache:s3cret@cache.internal:6379",
                "t1",
                List.of())));

        assertThat(findings().read().failures().getFirst().lastMessage())
                .isEqualTo("cannot reach redis://******@cache.internal:6379");
    }

    @Test
    void withoutTracingTracesAreNullAndFailuresEmpty() {
        RecentFindings.Findings read = new RecentFindings(
                        null, null, null, new MaskingEngine(), WINDOW, Clock.fixed(NOW, ZoneOffset.UTC))
                .read();

        assertThat(read.traces()).isNull();
        assertThat(read.failures()).isEmpty();
    }

    @Test
    void withoutInsightsThereAreNoProblems() {
        assertThat(findings().read().problems()).isEmpty();
    }

    @Test
    void listsTheCurrentProblems() {
        RecentFindings withInsights = new RecentFindings(
                TickedInsights.withConnectionsWaiting(),
                store,
                digest,
                new MaskingEngine(),
                WINDOW,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(withInsights.read().problems())
                .extracting(InsightsProblem::rule, InsightsProblem::severity)
                .contains(tuple(ProblemRule.POOL_WAITING, IssueSeverity.ERROR));
    }

    @Test
    void statesTheWindowItRead() {
        assertThat(findings().read().windowMs()).isEqualTo(900_000);
    }
}
