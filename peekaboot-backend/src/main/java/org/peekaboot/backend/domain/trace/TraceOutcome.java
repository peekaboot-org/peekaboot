package org.peekaboot.backend.domain.trace;

/** How a trace ended, or that it has not yet. An agent reads this verdict before anything else. */
public enum TraceOutcome {
    OK,
    ERROR,
    IN_PROGRESS;

    /** A trace still running has ended neither way, whatever its stored spans say so far. */
    public static TraceOutcome of(boolean complete, TraceStatus status) {
        if (!complete) {
            return IN_PROGRESS;
        }
        return status == TraceStatus.HAS_ERRORS ? ERROR : OK;
    }
}
