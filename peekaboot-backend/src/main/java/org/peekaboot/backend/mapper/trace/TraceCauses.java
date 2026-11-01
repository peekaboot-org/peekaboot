package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.mapper.trace.SpanWalk.Visit;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/**
 * The first failure an agent should look at. A failing span's stack trace comes first, then a
 * logged one, then a failing span that names its exception's type or message, then the first ERROR
 * log, then a SERVER span's 5xx. The deepest failing span is nearest the code that threw, since
 * each ancestor only passed the exception on.
 */
final class TraceCauses {

    /** A failing span with the exception info it carries, at its depth in the walk. */
    private record Failure(String spanId, SpanExceptionInfo exception, int depth) {}

    private TraceCauses() {}

    /**
     * Null where nothing failed the trace, where the caps evicted what did, or where the only
     * failure is a span that names no exception type or message.
     */
    static TraceSummary.@Nullable Cause of(TraceTree tree, List<TraceLog> errorLogs) {
        List<Failure> failures = SpanWalk.visits(tree.rootSpan(), tree.orphans())
                .filter(visit -> TraceReasons.failed(visit.span()))
                .flatMap(TraceCauses::failureOf)
                .toList();
        return deepest(failures.stream().filter(failure -> failure.exception().stackTrace() != null))
                .map(TraceCauses::spanCause)
                .or(() -> errorLogs.stream()
                        .filter(log -> log.stackTrace() != null)
                        .findFirst()
                        .map(TraceCauses::loggedExceptionCause))
                .or(() -> deepest(failures.stream().filter(TraceCauses::namesItsException))
                        .map(TraceCauses::spanCause))
                .or(() -> errorLogs.stream().findFirst().map(TraceCauses::loggedMessageCause))
                .or(() -> serverErrorCause(tree))
                .orElse(null);
    }

    private static Stream<Failure> failureOf(Visit visit) {
        SpanExceptionInfo exception = visit.span().exception();
        return exception == null
                ? Stream.empty()
                : Stream.of(new Failure(visit.span().spanId(), exception, visit.depth()));
    }

    /** Of two at the same depth, the one walked first, which started first. */
    private static Optional<Failure> deepest(Stream<Failure> failures) {
        return failures.reduce((deepest, candidate) -> candidate.depth() > deepest.depth() ? candidate : deepest);
    }

    /** A cause without a type or a message tells an agent nothing, so a log or a 5xx comes first. */
    private static boolean namesItsException(Failure failure) {
        return failure.exception().type() != null || failure.exception().message() != null;
    }

    private static TraceSummary.Cause spanCause(Failure failure) {
        SpanExceptionInfo exception = failure.exception();
        return new TraceSummary.Cause(
                exception.type(),
                exception.message(),
                applicationFrame(exception.stackTrace(), exception.applicationFrames()),
                failure.spanId());
    }

    /** A log names no exception class of its own, so the type and the message come from its trace's first line. */
    private static TraceSummary.Cause loggedExceptionCause(TraceLog log) {
        String stackTrace = Objects.requireNonNull(log.stackTrace(), "only a log with a stack trace is picked");
        String firstLine = stackTrace.split("\n", 2)[0];
        int separator = firstLine.indexOf(": ");
        return new TraceSummary.Cause(
                separator < 0
                        ? firstLine.strip()
                        : firstLine.substring(0, separator).strip(),
                separator < 0 ? null : firstLine.substring(separator + 2),
                applicationFrame(stackTrace, log.applicationFrames()),
                log.spanId());
    }

    private static TraceSummary.Cause loggedMessageCause(TraceLog log) {
        return new TraceSummary.Cause(null, log.message(), null, log.spanId());
    }

    private static Optional<TraceSummary.Cause> serverErrorCause(TraceTree tree) {
        return SpanWalk.depthFirst(tree.rootSpan(), tree.orphans())
                .filter(span -> HttpSpanTags.isServerError(span.kind(), span.tags()))
                .findFirst()
                .map(span -> new TraceSummary.Cause(null, TraceReasons.serverErrorDetail(span), null, span.spanId()));
    }

    /** The ranges index the lines folding split the trace into, which the wire joined with a newline. */
    private static @Nullable String applicationFrame(@Nullable String stackTrace, List<Range> applicationFrames) {
        if (stackTrace == null || applicationFrames.isEmpty()) {
            return null;
        }
        String line = stackTrace.split("\n", -1)[applicationFrames.getFirst().start()].strip();
        return line.startsWith("at ") ? line.substring("at ".length()) : line;
    }
}
