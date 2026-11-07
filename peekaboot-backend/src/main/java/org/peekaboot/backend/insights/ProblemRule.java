package org.peekaboot.backend.insights;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.insights.config.Unit;

/**
 * What the insights data checks a window for. Each rule reads the series it names, which are the
 * bundled panels' ids, and is skipped where one of them is disabled, unresolved or holds no sample.
 * {@code sentence} takes the measured value and then the threshold.
 */
public enum ProblemRule {
    CPU_HIGH(
            IssueSeverity.WARNING,
            Unit.PERCENT,
            "Process CPU ran at a median of %s percent, at or above %s.",
            "cpu.process"),
    GC_OVERHEAD(
            IssueSeverity.WARNING,
            Unit.PERCENT,
            "Garbage collection took a median of %s percent of CPU time, at or above %s.",
            "cpu.gc-overhead"),
    HEAP_NEAR_MAX(
            IssueSeverity.WARNING,
            Unit.PERCENT,
            "The heap in use had a median of %s percent of its maximum, at or above %s.",
            "heap.used",
            "heap.max"),
    THREAD_GROWTH(
            IssueSeverity.WARNING,
            Unit.COUNT,
            "The fewest live threads grew by %s from the window's first quarter to its last, at or above %s.",
            "threads.live"),
    POOL_WAITING(
            IssueSeverity.ERROR,
            Unit.COUNT,
            "The pool's pending connections peaked at %s, at or above %s.",
            "db-pool.pending"),
    SERVER_ERRORS(
            IssueSeverity.ERROR,
            Unit.PERCENT,
            "%s percent of HTTP requests answered with a 5xx, at or above %s.",
            "http-throughput.server-errors",
            "http-throughput.requests"),
    ERROR_LOGS(
            IssueSeverity.WARNING,
            Unit.COUNT,
            "Lines logged at ERROR numbered %s, at or above %s.",
            "log-events.errors"),
    SLOW_HTTP(
            IssueSeverity.WARNING,
            Unit.MILLIS,
            "The average HTTP latency had a 95th percentile of %s ms, at or above %s.",
            "http-latency.avg");

    private final IssueSeverity severity;
    private final Unit unit;
    private final String sentence;
    private final String measured;

    @Nullable
    private final String against;

    ProblemRule(IssueSeverity severity, Unit unit, String sentence, String measured) {
        this(severity, unit, sentence, measured, null);
    }

    /** {@code against} is the series {@code measured} is set against, as used heap against the maximum. */
    ProblemRule(IssueSeverity severity, Unit unit, String sentence, String measured, @Nullable String against) {
        this.severity = severity;
        this.unit = unit;
        this.sentence = sentence;
        this.measured = measured;
        this.against = against;
    }

    IssueSeverity severity() {
        return severity;
    }

    Unit unit() {
        return unit;
    }

    String sentence() {
        return sentence;
    }

    /** The series ids the rule reads, the one it measures first. */
    List<String> series() {
        return against == null ? List.of(measured) : List.of(measured, against);
    }
}
