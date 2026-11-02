package org.peekaboot.backend.service;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.problem.RequestParameters;

/** The {@code limit} every listing endpoint reads, which caps the rows or lines one page serves. */
final class PageLimit {

    static final int DEFAULT = 100;

    /** Bounds one response's size; a client pages past it with the cursor. */
    static final int MAX = 10_000;

    private PageLimit() {}

    /** {@link #DEFAULT} where the request sent none; refuses a value outside 1 to {@link #MAX}. */
    static int of(@Nullable String limit) {
        Integer read = RequestParameters.integer("limit", limit, 1, MAX);
        return read != null ? read : DEFAULT;
    }
}
