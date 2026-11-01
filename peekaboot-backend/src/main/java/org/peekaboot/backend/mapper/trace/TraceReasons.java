package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceReason.Kind;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/**
 * Why a trace is in Errors or Slow, read off its mapped spans and logs. The rules are the store's
 * admission rules, so on a whole trace a reason names what put the trace in its bucket. An async
 * row is judged on its own subtree and window, which the store never admits by.
 */
final class TraceReasons {

    private TraceReasons() {}

    /** Failures by kind in tree order, then the ERROR logs as they arrived, then slowness. */
    static List<TraceReason> of(TraceTree tree, List<TraceLog> errorLogs, long slowTraceThresholdMs) {
        List<SpanNode> spans =
                SpanWalk.depthFirst(tree.rootSpan(), tree.orphans()).toList();
        List<TraceReason> reasons = new ArrayList<>();
        for (SpanNode span : spans) {
            if (failed(span)) {
                reasons.add(new TraceReason(Kind.EXCEPTION, span.spanId(), exceptionDetail(span.exception())));
            }
        }
        for (SpanNode span : spans) {
            if (HttpSpanTags.isServerError(span.kind(), span.tags())) {
                reasons.add(new TraceReason(Kind.SERVER_ERROR, span.spanId(), serverErrorDetail(span)));
            }
        }
        for (TraceLog log : errorLogs) {
            reasons.add(new TraceReason(Kind.ERROR_LOG, log.spanId(), log.message()));
        }
        if (tree.durationMs() >= slowTraceThresholdMs) {
            reasons.add(new TraceReason(
                    Kind.SLOW,
                    tree.rootSpan() != null ? tree.rootSpan().spanId() : null,
                    "Took " + tree.durationMs() + " ms, at or above the " + slowTraceThresholdMs
                            + " ms slow-trace threshold."));
        }
        return List.copyOf(reasons);
    }

    /** The one meaning of a failing span, which the EXCEPTION reason reports. */
    static boolean failed(SpanNode span) {
        return span.status() == SpanStatus.ERROR;
    }

    static String serverErrorDetail(SpanNode span) {
        return "Answered with status " + HttpSpanTags.statusCode(span.tags()) + ".";
    }

    private static String exceptionDetail(@Nullable SpanExceptionInfo exception) {
        if (exception == null || (exception.type() == null && exception.message() == null)) {
            return "The span ended in error without naming why.";
        }
        if (exception.type() == null) {
            return exception.message();
        }
        return exception.message() == null ? exception.type() : exception.type() + ": " + exception.message();
    }
}
