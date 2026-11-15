package org.peekaboot.backend.tracing.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * The duplicates a bundle folded away and the parent index it finds them through. A folded-away
 * duplicate's id is kept in a redirect table so a later span whose stated parent was already
 * folded away - a grandchild of the duplicate, which arrives before the duplicate does - still
 * resolves to the surviving ancestor. The table is pruned as spans are evicted (see
 * {@link #evicted}), so it stays bounded by the bundle's span cap rather than growing for the
 * trace's whole life.
 *
 * <p>Owned by one {@link TraceDataBundle} and called only under its span lock. The bundle's
 * class Javadoc says why a fold has to check both directions.
 */
final class DuplicateFolds {

    private final Map<String, String> parentRedirects = new HashMap<>();
    /** Reverse index of {@link #parentRedirects}, so an evicted survivor's redirects can be pruned. */
    private final Map<String, List<String>> redirectsPointingAt = new HashMap<>();

    private final Map<String, List<String>> childrenByParentId = new HashMap<>();

    /**
     * The stored parent {@code span} duplicates, recorded as {@code span}'s redirect; null where
     * it duplicates none. The uncommon ordering, but possible once earlier folds have populated
     * {@link #parentRedirects}.
     */
    @Nullable
    SpanData foldIntoStoredParent(SpanData span, Map<String, SpanData> stored) {
        String resolvedParentId = resolve(span.parentId());
        SpanData parent = resolvedParentId != null ? stored.get(resolvedParentId) : null;
        if (parent != null && SpanDuplicateMatcher.isDuplicate(span, parent)) {
            recordRedirect(span.spanId(), parent.spanId());
            return parent;
        }
        return null;
    }

    /** Indexes a span the bundle just stored under its parent. */
    void stored(SpanData span) {
        if (span.parentId() != null) {
            childrenByParentId
                    .computeIfAbsent(span.parentId(), k -> new ArrayList<>())
                    .add(span.spanId());
        }
    }

    /**
     * The span just stored may be the real span whose duplicate already arrived as one of
     * its direct children - the expected ordering, since a duplicate ends (and so exports)
     * before the real span containing it does. Removes any such child from {@code stored},
     * redirects it to {@code survivor} and returns it.
     */
    List<SpanData> absorbDuplicateChildren(SpanData survivor, Map<String, SpanData> stored) {
        List<String> children = childrenByParentId.get(survivor.spanId());
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<SpanData> absorbed = new ArrayList<>();
        Iterator<String> it = children.iterator();
        while (it.hasNext()) {
            String childId = it.next();
            SpanData candidate = stored.get(childId);
            if (candidate != null && SpanDuplicateMatcher.isDuplicate(candidate, survivor)) {
                stored.remove(childId);
                recordRedirect(childId, survivor.spanId());
                absorbed.add(candidate);
                it.remove();
            }
        }
        return absorbed;
    }

    /** Forgets an evicted span: its place under its parent, its children entry, every redirect into it. */
    void evicted(SpanData span) {
        removeFromParentIndex(span);
        childrenByParentId.remove(span.spanId());
        pruneRedirectsPointingAt(span.spanId());
    }

    /**
     * Follows the redirect chain for a (possibly folded-away) span id to the span it
     * ultimately survived as. Returns {@code spanId} unchanged if it was never redirected.
     */
    @Nullable
    String resolve(@Nullable String spanId) {
        String current = spanId;
        while (current != null && parentRedirects.containsKey(current)) {
            current = parentRedirects.get(current);
        }
        return current;
    }

    /**
     * The ids of the stored spans whose resolved parent is {@code spanId}. {@link #childrenByParentId}
     * is keyed on the raw parent id, so a child naming a folded-away duplicate of {@code spanId}
     * sits under the duplicate's id, and {@link #redirectsPointingAt} lists every such duplicate.
     */
    List<String> resolvedChildIdsOf(String spanId) {
        List<String> childIds = new ArrayList<>(childrenByParentId.getOrDefault(spanId, List.of()));
        for (String duplicateId : redirectsPointingAt.getOrDefault(spanId, List.of())) {
            childIds.addAll(childrenByParentId.getOrDefault(duplicateId, List.of()));
        }
        return childIds;
    }

    /** Whether {@code spanId} folded into a span that is still stored. */
    boolean foldedAway(String spanId) {
        return parentRedirects.containsKey(spanId);
    }

    /** The redirects held; what the pruning keeps bounded. */
    int redirectCount() {
        return parentRedirects.size();
    }

    /**
     * Records that {@code removedId} folded into {@code survivorId}, maintaining
     * {@link #redirectsPointingAt} alongside {@link #parentRedirects} so the entry can be
     * pruned in {@link #evicted} once {@code survivorId} itself is evicted.
     */
    private void recordRedirect(String removedId, String survivorId) {
        parentRedirects.put(removedId, survivorId);
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).add(removedId);
        retargetChainedRedirects(removedId, survivorId);
    }

    /**
     * {@code removedId} may itself have been an earlier fold's survivor - i.e. other ids
     * already redirect to it (triple-nested duplicates; does not occur in production, but
     * the fold must stay correct if it ever did). A folded-away id, unlike a real span, is
     * never evicted, so entries keyed on it would never be pruned. Re-point them directly
     * at {@code survivorId} so they are pruned together with it once it is evicted.
     */
    private void retargetChainedRedirects(String removedId, String survivorId) {
        List<String> chained = redirectsPointingAt.remove(removedId);
        if (chained == null || chained.isEmpty()) {
            return;
        }
        chained.forEach(id -> parentRedirects.put(id, survivorId));
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).addAll(chained);
    }

    /** Redirects into an evicted span can never resolve again. */
    private void pruneRedirectsPointingAt(String evictedSpanId) {
        List<String> redirected = redirectsPointingAt.remove(evictedSpanId);
        if (redirected != null) {
            redirected.forEach(parentRedirects::remove);
        }
    }

    private void removeFromParentIndex(SpanData span) {
        if (span.parentId() == null) {
            return;
        }
        List<String> siblings = childrenByParentId.get(span.parentId());
        if (siblings == null) {
            return;
        }
        siblings.remove(span.spanId());
        if (siblings.isEmpty()) {
            childrenByParentId.remove(span.parentId());
        }
    }
}
