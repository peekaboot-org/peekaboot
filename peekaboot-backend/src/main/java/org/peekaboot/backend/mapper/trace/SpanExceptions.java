package org.peekaboot.backend.mapper.trace;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.event.CapturedExceptionInfo;

/** A failed span's exception as the API serves it: the message masked like a tag, the trace masked and folded like a log's. */
final class SpanExceptions {

    private final MaskingEngine maskingEngine;
    private final WireStackTraces stackTraces;

    SpanExceptions(MaskingEngine maskingEngine, StackTraceRules stackTraceRules) {
        this.maskingEngine = maskingEngine;
        this.stackTraces = new WireStackTraces(maskingEngine, stackTraceRules);
    }

    @Nullable
    SpanExceptionInfo of(@Nullable CapturedExceptionInfo captured) {
        if (captured == null) {
            return null;
        }
        WireStackTraces.WireStackTrace trace = stackTraces.of(captured.stackTrace());
        return new SpanExceptionInfo(
                captured.type(),
                maskingEngine.maskValue(captured.message()),
                trace.text(),
                trace.hiddenFrames(),
                trace.applicationFrames());
    }
}
