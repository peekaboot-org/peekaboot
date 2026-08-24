package org.peekaboot.backend.tracing.store;

import java.util.Locale;

public enum TraceBucket {
    ALL,
    ERRORS,
    SLOW;

    /**
     * Lenient parse for query params: null, blank, or unknown values fall
     * back to ALL.
     */
    public static TraceBucket fromParam(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
