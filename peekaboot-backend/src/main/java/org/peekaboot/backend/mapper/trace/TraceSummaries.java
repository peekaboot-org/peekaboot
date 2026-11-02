package org.peekaboot.backend.mapper.trace;

import java.util.List;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

/** A trace's summary, built from the judged tree and the facts the store holds beside it. */
final class TraceSummaries {

    private final long slowTraceThresholdMs;

    TraceSummaries(PeekabootTracingProperties properties) {
        this.slowTraceThresholdMs = properties.getSlowTraceThresholdMs();
    }

    TraceSummary of(TraceTree tree, TraceFacts facts) {
        List<TraceReason> reasons = TraceReasons.of(tree, facts.errorLogs(), slowTraceThresholdMs);
        boolean failed = facts.storeFailed()
                || reasons.stream().anyMatch(reason -> reason.kind().failure());
        return new TraceSummary(
                tree.traceId(),
                tree.startTime(),
                tree.durationMs(),
                tree.complete(),
                tree.run(),
                tree.rootActionType(),
                tree.rootOperation(),
                tree.request(),
                TraceOutcome.of(tree.complete(), tree.interrupted(), failed),
                facts.buckets().stream().sorted().toList(),
                reasons,
                TraceCauses.of(tree, facts.errorLogs()),
                issuesOf(tree),
                Hotspots.of(tree),
                countsOf(tree, facts),
                tree.subtree());
    }

    private static List<TraceIssue> issuesOf(TraceTree tree) {
        return SpanWalk.depthFirst(tree.rootSpan(), tree.orphans())
                .flatMap(span -> span.issues().stream())
                .toList();
    }

    private static TraceSummary.Counts countsOf(TraceTree tree, TraceFacts facts) {
        List<LogCapturedEvent> logs = facts.logs();
        int errors = (int) logs.stream().filter(LogCapturedEvent::isError).count();
        int warnings = (int) logs.stream().filter(LogCapturedEvent::isWarn).count();
        return new TraceSummary.Counts(
                tree.spanCount(),
                tree.queries(),
                new TraceSummary.LogCounts(logs.size(), errors, warnings),
                facts.droppedSpans(),
                facts.droppedLogs());
    }
}
