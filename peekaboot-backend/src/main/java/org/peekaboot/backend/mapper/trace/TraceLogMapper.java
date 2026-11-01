package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/**
 * A trace's captured logs as the dashboard shows them: each log named by the span that survived
 * folding, its stack trace folded.
 */
public final class TraceLogMapper {

    private final MaskingEngine maskingEngine;

    private final WireStackTraces stackTraces;

    TraceLogMapper(MaskingEngine maskingEngine, StackTraceRules stackTraceRules) {
        this.maskingEngine = maskingEngine;
        this.stackTraces = new WireStackTraces(maskingEngine, stackTraceRules);
    }

    /**
     * Resolves a log's MDC span id through {@code bundle}, each id once. A redirect can move while spans
     * arrive, so one row reads every log's span through one of these and its parts agree on it.
     */
    static UnaryOperator<String> resolvingOnce(TraceDataBundle bundle) {
        Map<String, String> resolved = new HashMap<>();
        return spanId -> resolved.computeIfAbsent(spanId, bundle::resolveSpanId);
    }

    /**
     * {@code logs} is one read of the bundle's {@code capturedLogs()}, so the caller can count the same
     * list it maps. {@code spanOf} is the row's {@link #resolvingOnce}.
     */
    List<TraceLog> map(UnaryOperator<String> spanOf, List<LogCapturedEvent> logs) {
        return logs.stream().map(e -> toTraceLog(spanOf, e)).toList();
    }

    /**
     * The ERROR entries of {@code mapped}, which is {@link #map} of {@code logs}. Picked by index,
     * so {@link LogCapturedEvent#isError} stays the one ERROR test and each entry is the list's own.
     */
    static List<TraceLog> errorsAmong(List<LogCapturedEvent> logs, List<TraceLog> mapped) {
        return IntStream.range(0, logs.size())
                .filter(index -> logs.get(index).isError())
                .mapToObj(mapped::get)
                .toList();
    }

    /**
     * The logs among {@code logs}, one read of the bundle's, emitted inside one subtree's own
     * spans. A subtree row reports the work it contains, so the enclosing request's log count and
     * the error badge it puts on the row are not its to show. Resolved through {@code spanOf}, the
     * row's {@link #resolvingOnce}, since a log's MDC span id may name a span folded away since and
     * the span that admits a log must be the span it is served under.
     */
    static List<LogCapturedEvent> logsWithin(
            SpanNode subtreeRoot, UnaryOperator<String> spanOf, List<LogCapturedEvent> logs) {
        Set<String> spanIds = new HashSet<>();
        collectSpanIds(subtreeRoot, spanIds);
        return logs.stream()
                .filter(log -> log.spanId() != null && spanIds.contains(spanOf.apply(log.spanId())))
                .toList();
    }

    private static void collectSpanIds(SpanNode node, Set<String> spanIds) {
        if (node == null) {
            return;
        }
        spanIds.add(node.spanId());
        node.children().forEach(child -> collectSpanIds(child, spanIds));
    }

    /**
     * Folding happens here rather than at capture: the full trace stays on the event, and these
     * ranges are only the rendering hint the browser needs so it classifies nothing itself. The
     * message and trace are masked like a failed span's exception: either can echo the failing
     * request's URL.
     */
    private TraceLog toTraceLog(UnaryOperator<String> spanOf, LogCapturedEvent e) {
        WireStackTraces.WireStackTrace trace = stackTraces.of(e.stackTrace());
        return new TraceLog(
                e.traceId(),
                spanOf.apply(e.spanId()),
                e.timestamp(),
                e.level(),
                e.logger(),
                e.thread(),
                maskingEngine.maskValue(e.message()),
                trace.text(),
                trace.hiddenFrames(),
                trace.applicationFrames());
    }
}
