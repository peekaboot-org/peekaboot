package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;

/**
 * One captured log line, with its throwable folded for rendering.
 *
 * @param stackTrace {@code ThrowableProxyUtil.asString}'s rendering, joined back from the lines
 *     folding split it into so the browser's own {@code split('\n')} indexes the same lines
 *     these ranges do; null where the log carried no throwable. Close to but not identical to
 *     {@code Throwable.printStackTrace}'s own output: an elided run reads "... N common frames
 *     omitted" rather than "... N more", and enabling Logback's packaging data would append
 *     " [jar:version]" to every frame - inside the substring an exclusion pattern matches
 *     against, so a jar path containing an excluded token could hide a frame. Nothing in this
 *     codebase enables packaging data today.
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
        List<Range> applicationFrames) {

    /** The same log with its throwable stripped - what the span tree carries, since it only ever counts logs. */
    public TraceLog withoutStackTrace() {
        return new TraceLog(spanId, timestamp, level, loggerName, message, threadName, null, List.of(), List.of());
    }
}
