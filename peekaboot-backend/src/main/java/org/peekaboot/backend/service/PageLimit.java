package org.peekaboot.backend.service;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.problem.RequestParameters;

/** The {@code limit} every listing endpoint reads, which caps the rows or lines one page serves. */
final class PageLimit {

    static final int DEFAULT = 100;

    /** Bounds one response's size; a client pages past it with the cursor. */
    static final int MAX = 10_000;

    /** Bounds a page whose rows carry their data; {@link #DEFAULT} fits, so a request without a limit is served. */
    static final int MAX_FULL = 100;

    private PageLimit() {}

    /** {@link #DEFAULT} where the request sent none; refuses a value outside 1 to {@link #MAX}. */
    static int of(@Nullable String limit) {
        Integer read = RequestParameters.integer("limit", limit, 1, MAX);
        return read != null ? read : DEFAULT;
    }

    /** {@link #of(String)} for a trace list; refuses a value above {@link #MAX_FULL} where {@code view} is full. */
    static int of(@Nullable String limit, TraceView view) {
        int read = of(limit);
        if (view == TraceView.FULL && read > MAX_FULL) {
            throw ApiProblemException.badParameter(
                    "limit",
                    "limit must be at most " + MAX_FULL + " with view=full, got '" + limit
                            + "'. Read on with the next value, or list with view=summary and fetch a trace by its id.");
        }
        return read;
    }
}
