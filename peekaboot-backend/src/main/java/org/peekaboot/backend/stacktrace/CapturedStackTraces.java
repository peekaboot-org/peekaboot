package org.peekaboot.backend.stacktrace;

import java.util.List;
import org.peekaboot.backend.text.CountFormat;

/** The one bound on a stack trace Peekaboot keeps, whether a log line or a failed span carried it. */
public final class CapturedStackTraces {

    /**
     * A StackOverflowError's own trace runs to 1024 frames by default, and a developer hits
     * one routinely. The cap sits far above an ordinary trace of 30 to 80 lines and bounds a
     * single record before that record can dominate the trace store's retention.
     */
    public static final int MAX_LINES = 1000;

    private CapturedStackTraces() {}

    /** {@code trace} itself within {@link #MAX_LINES}, else its first {@link #MAX_LINES} lines and one saying how many were dropped. */
    public static String capLines(String trace) {
        List<String> lines = trace.lines().toList();
        if (lines.size() <= MAX_LINES) {
            return trace;
        }
        int omitted = lines.size() - MAX_LINES;
        return String.join("\n", lines.subList(0, MAX_LINES)) + "\n... " + CountFormat.of(omitted, "line") + " omitted";
    }
}
