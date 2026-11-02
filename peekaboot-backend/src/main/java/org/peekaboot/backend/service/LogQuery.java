package org.peekaboot.backend.service;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.log.LogSearchParameters;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

/**
 * A log search request, every parameter read and checked.
 *
 * @param level the lowest level kept; null keeps every level
 * @param text {@code q} in lower case; null matches every line
 * @param loggerPrefix kept where the logger's name starts with it, as written
 * @param traceId the one trace searched; null searches every trace and the untraced logs
 * @param before the sequence of the last line the previous page served; null for the first page
 */
record LogQuery(
        @Nullable LogLevel level,
        @Nullable String text,
        @Nullable String loggerPrefix,
        @Nullable String traceId,
        TimeWindow window,
        int limit,
        @Nullable Long before) {

    static LogQuery of(LogSearchParameters parameters) {
        String level = RequestParameters.text(parameters.level());
        String text = RequestParameters.text(parameters.q());
        return new LogQuery(
                level == null ? null : RequestParameters.enumValue("level", LogLevel.class, level),
                text == null ? null : text.toLowerCase(Locale.ROOT),
                RequestParameters.text(parameters.logger()),
                RequestParameters.text(parameters.traceId()),
                TimeWindow.of(parameters.since(), parameters.until()),
                PageLimit.of(parameters.limit()),
                Cursors.log(parameters.cursor()));
    }

    /** The filters that read a captured line as it is stored, before anything is mapped or masked. */
    boolean admits(LogCapturedEvent log) {
        return isAtLeastLevel(log) && isFromLogger(log) && window.contains(log.timestamp()) && isBeforeCursor(log);
    }

    /** {@code q} against what the response shows, the masked message and stack trace, so it never finds what masking hides. */
    boolean matchesText(TraceLog log) {
        return text == null || contains(log.message()) || contains(log.stackTrace());
    }

    private boolean isAtLeastLevel(LogCapturedEvent log) {
        return level == null || isAtLeast(LogLevel.of(log.level()), level);
    }

    private static boolean isAtLeast(@Nullable LogLevel captured, LogLevel lowest) {
        return captured != null && captured.compareTo(lowest) >= 0;
    }

    private boolean isFromLogger(LogCapturedEvent log) {
        return loggerPrefix == null || log.logger().startsWith(loggerPrefix);
    }

    private boolean isBeforeCursor(LogCapturedEvent log) {
        return before == null || log.sequence() < before;
    }

    /** A literal substring in any case: {@code q} is no pattern, so {@code (} and {@code *} match themselves. */
    private boolean contains(@Nullable String shown) {
        return shown != null && shown.toLowerCase(Locale.ROOT).contains(text);
    }
}
