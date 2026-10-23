package org.peekaboot.backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/**
 * A trace's captured logs as the dashboard shows them: each log named by the span that survived
 * folding, its stack trace folded, and the list's badges counted by level.
 */
final class TraceLogMapper {

    private final StackTraceRules stackTraceRules;

    TraceLogMapper(StackTraceRules stackTraceRules) {
        this.stackTraceRules = stackTraceRules;
    }

    /** {@code logs} is one read of {@code bundle.logs()}, so the caller can count the same list it maps. */
    List<TraceLog> map(TraceDataBundle bundle, List<LogCapturedEvent> logs) {
        return logs.stream().map(e -> toTraceLog(bundle, e)).toList();
    }

    /**
     * The logs emitted inside one subtree's own spans. A subtree row reports the work it
     * contains, so the enclosing request's log count - and the error badge that count puts on
     * the row - are not its to show. Resolved through the bundle for the same reason the
     * detail's logs are: a log's MDC span id may name a span folded away since.
     */
    List<LogCapturedEvent> logsWithin(SpanNode subtreeRoot, TraceDataBundle bundle) {
        Set<String> spanIds = new HashSet<>();
        collectSpanIds(subtreeRoot, spanIds);
        return bundle.logs().stream()
                .filter(log -> log.spanId() != null && spanIds.contains(bundle.resolveSpanId(log.spanId())))
                .toList();
    }

    private static void collectSpanIds(SpanNode node, Set<String> spanIds) {
        if (node == null) {
            return;
        }
        spanIds.add(node.spanId());
        node.children().forEach(child -> collectSpanIds(child, spanIds));
    }

    /** The list's log badges: counted from the logs the bundle already carries, so no extra lookup. */
    TraceTree withLogsSummary(TraceTree tree, List<LogCapturedEvent> logs) {
        TraceTabSummary summary = tree.summary();
        return tree.withSummary(
                new TraceTabSummary(summary.request(), summary.spans(), summary.queries(), logsSummary(logs)));
    }

    private static TraceTabSummary.LogsSummary logsSummary(List<LogCapturedEvent> logs) {
        int errors = (int) logs.stream().filter(LogCapturedEvent::isError).count();
        int warnings = (int) logs.stream().filter(LogCapturedEvent::isWarn).count();
        return new TraceTabSummary.LogsSummary(logs.size(), errors, warnings);
    }

    /**
     * Folding happens here rather than at capture: the full trace stays on the event, and these
     * ranges are only the rendering hint the browser needs so it classifies nothing itself.
     */
    private TraceLog toTraceLog(TraceDataBundle bundle, LogCapturedEvent e) {
        String wireStackTrace = null;
        List<Range> hiddenFrames = List.of();
        List<Range> applicationFrames = List.of();
        if (e.stackTrace() != null) {
            FoldedTrace folded = stackTraceRules.fold(e.stackTrace());
            // The ranges index into folded.lines(), not the raw captured string: String.lines() splits
            // on a lone \r where the browser's split('\n') would not, so sending anything else back
            // would leave the two disagreeing about which line a given index names.
            wireStackTrace = String.join("\n", folded.lines());
            hiddenFrames = folded.hidden();
            applicationFrames = folded.applicationFrames();
        }
        return new TraceLog(
                bundle.resolveSpanId(e.spanId()),
                e.timestamp(),
                e.level(),
                e.message(),
                wireStackTrace,
                hiddenFrames,
                applicationFrames);
    }
}
