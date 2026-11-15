package org.peekaboot.backend.tracing.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.Tags;

/**
 * Decides whether two spans are the same JDBC call captured twice - a host with two
 * datasource decorators emits a span per decorator for every call, nested one under the
 * other, differing only in which service-identifier tag carries the real datasource name. Used by
 * {@link TraceDataBundle}, which folds duplicates away incrementally as spans are written.
 *
 * <p>Name and tags alone also match a method that calls itself under {@code @Observed}, so both
 * spans have to be datasource spans. A twin that failed where the other did not holds the only
 * record of that failure, so both have failed or neither has.
 */
final class SpanDuplicateMatcher {

    /**
     * One key per datasource decorator. A third would make the triple-nested fold in
     * {@link TraceDataBundle} reachable - see docs/ARCHITECTURE.md, <em>Span Deduplication</em>.
     */
    private static final Set<String> SERVICE_IDENTIFIER_KEYS = Set.of("peer.service", "jdbc.datasource.name");

    private SpanDuplicateMatcher() {}

    /**
     * True if {@code a} and {@code b} are datasource spans that both failed or both did not, and
     * carry the same name and the same tags, ignoring whichever of the two service-identifier keys
     * each happens to set. Twins that both failed recorded the same exception, so the survivor
     * keeps it. Every condition holds per span or is an equality, which keeps the relation transitive.
     */
    static boolean isDuplicate(SpanData a, SpanData b) {
        return isDatasourceSpan(a) && isDatasourceSpan(b) && a.hasError() == b.hasError() && isSameCall(a, b);
    }

    /**
     * Every datasource-micrometer convention lists a {@code jdbc.} key and its OpenTelemetry query
     * convention lists {@code db.} keys, so a key with either prefix marks a datasource span.
     */
    private static boolean isDatasourceSpan(SpanData span) {
        Map<String, String> tags = span.tags();
        return tags != null && Tags.anyKeyStartsWith(tags, "jdbc.", "db.");
    }

    private static boolean isSameCall(SpanData a, SpanData b) {
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
