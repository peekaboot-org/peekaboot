package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;

/**
 * One captured log line, with its throwable folded for rendering.
 *
 * @param stackTrace the captured throwable (see
 *     {@link org.peekaboot.backend.tracing.event.LogCapturedEvent#stackTrace()}), joined back from
 *     the lines folding split it into so the browser's own {@code split('\n')} indexes the same
 *     lines these ranges do; null where the log carried no throwable
 * @param hiddenFrames the framework frames a reader is spared, empty where {@code stackTrace} is
 *     null or where folding is switched off
 * @param applicationFrames the frames that are the application's own, empty where {@code stackTrace} is null
 */
public record TraceLog(
        String spanId,
        Instant timestamp,
        String level,
        String loggerName,
        String message,
        String threadName,
        String stackTrace,
        List<Range> hiddenFrames,
        List<Range> applicationFrames) {}
