package org.peekaboot.backend.domain.trace;

/** How a trace ended, or that it has not yet. An agent reads this verdict before anything else. */
public enum TraceOutcome {
    OK,
    ERROR,
    IN_PROGRESS,
    /** Stored by an earlier run of the application, which stopped before the trace's root arrived. */
    INTERRUPTED;

    /** A trace still running has ended neither way, whatever its stored spans say so far; one whose run ended never will. */
    public static TraceOutcome of(boolean complete, boolean interrupted, boolean failed) {
        if (!complete) {
            return interrupted ? INTERRUPTED : IN_PROGRESS;
        }
        return failed ? ERROR : OK;
    }
}
