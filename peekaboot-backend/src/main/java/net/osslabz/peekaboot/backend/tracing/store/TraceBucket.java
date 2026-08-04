package net.osslabz.peekaboot.backend.tracing.store;

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
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
