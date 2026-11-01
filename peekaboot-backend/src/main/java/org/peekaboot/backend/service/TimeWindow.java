package org.peekaboot.backend.service;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.problem.RequestParameters;

/**
 * The {@code since} and {@code until} a listing request names, half open: {@code since} is in the
 * window and {@code until} is not. Either may be absent, and an absent bound leaves that side open.
 */
record TimeWindow(@Nullable Instant since, @Nullable Instant until) {

    static TimeWindow of(@Nullable String since, @Nullable String until) {
        Instant from = RequestParameters.instant("since", since);
        Instant to = RequestParameters.instant("until", until);
        if (from != null && to != null && !to.isAfter(from)) {
            throw ApiProblemException.badParameter(
                    "until", "until must be later than since, got since " + from + " and until " + to + ".");
        }
        return new TimeWindow(from, to);
    }

    boolean isOpen() {
        return since == null && until == null;
    }

    /** An instant never recorded is outside every window but the open one. */
    boolean contains(@Nullable Instant instant) {
        return instant == null ? isOpen() : isNotBeforeSince(instant) && isBeforeUntil(instant);
    }

    private boolean isNotBeforeSince(Instant instant) {
        return since == null || !instant.isBefore(since);
    }

    private boolean isBeforeUntil(Instant instant) {
        return until == null || instant.isBefore(until);
    }
}
