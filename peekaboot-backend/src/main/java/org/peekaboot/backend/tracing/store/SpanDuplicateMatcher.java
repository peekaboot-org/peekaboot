package org.peekaboot.backend.tracing.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether two spans are the same JDBC call captured twice - Peekaboot's JDBC
 * instrumentation double-emits a span for every call, nested one under the other, differing
 * only in which service-identifier tag carries the real datasource name. Used by
 * {@link TraceDataBundle}, which folds duplicates away incrementally as spans are written.
 */
public final class SpanDuplicateMatcher {

    private static final Set<String> SERVICE_IDENTIFIER_KEYS = Set.of("peer.service", "jdbc.datasource.name");

    private SpanDuplicateMatcher() {}

    /** True if {@code a} and {@code b} carry the same name and the same tags, ignoring
     * whichever of the two service-identifier keys each happens to set. */
    public static boolean isDuplicate(SpanData a, SpanData b) {
        return a.name().equals(b.name()) && filterServiceKeys(a.tags()).equals(filterServiceKeys(b.tags()));
    }

    private static Map<String, String> filterServiceKeys(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> filtered = new HashMap<>(tags);
        SERVICE_IDENTIFIER_KEYS.forEach(filtered::remove);
        return filtered;
    }
}
