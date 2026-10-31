package org.peekaboot.backend.domain.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;

/**
 * Why a span failed, as the API serves it. The trace fields mean what they mean on a {@link TraceLog}.
 *
 * @param type the exception's class; null where the span failed without an exception event or the SDK could not name the class
 * @param message masked by value shape; the status description where the exception had no message
 * @param stackTrace masked, then joined from the lines folding indexed; null without an exception event
 * @param hiddenFrames the framework frames a reader is spared, empty where folding is off or there is no trace
 * @param applicationFrames the application's own frames, empty where there is no trace
 */
public record SpanExceptionInfo(
        @Nullable String type,
        @Nullable String message,
        @Nullable String stackTrace,
        List<Range> hiddenFrames,
        List<Range> applicationFrames) {}
