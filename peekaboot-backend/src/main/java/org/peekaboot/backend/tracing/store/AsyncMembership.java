package org.peekaboot.backend.tracing.store;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which of a bundle's spans belong to an async subtree, and the synchronous window over the
 * rest. Owned by one {@link TraceDataBundle} and called only under its lock: the bundle knows
 * the span tree and says which spans enter, join, leave or are evicted.
 */
final class AsyncMembership {

    private final Set<String> members = new HashSet<>();
    // Spans the cap evicted while synchronous. Every refresh starts from here, so eviction never
    // lowers the window: TraceData.duration must stay the number the Slow bucket admitted by.
    private Window evictedSyncWindow = Window.EMPTY;
    private Window syncWindow = Window.EMPTY;
    private boolean syncWindowStale;

    boolean contains(@Nullable String spanId) {
        return spanId != null && members.contains(spanId);
    }

    boolean isEmpty() {
        return members.isEmpty();
    }

    Set<String> members() {
        return Set.copyOf(members);
    }

    Window syncWindow() {
        return syncWindow;
    }

    /** A span stored outside every async subtree. */
    void foldSynchronous(SpanData span) {
        syncWindow = syncWindow.including(span);
    }

    /** A span that enters a subtree as it arrives, so it never counted as synchronous. */
    void enter(String spanId) {
        members.add(spanId);
    }

    /** A stored span joining a subtree; false if it already belonged to one. */
    boolean join(SpanData span) {
        if (!members.add(span.spanId())) {
            return false;
        }
        if (syncWindow.isBoundedBy(span)) {
            syncWindowStale = true;
        }
        return true;
    }

    /**
     * A duplicate folded into its survivor. The survivor covers the same window only while it
     * stays synchronous, so a duplicate on an edge forces a refold once the survivor is placed.
     */
    void forget(SpanData span) {
        members.remove(span.spanId());
        if (syncWindow.isBoundedBy(span)) {
            syncWindowStale = true;
        }
    }

    void evict(SpanData span) {
        if (!members.remove(span.spanId())) {
            evictedSyncWindow = evictedSyncWindow.including(span);
        }
    }

    /** A min/max pair cannot drop one span, so the window is refolded once a span on its edge joined. */
    void refresh(Collection<SpanData> stored) {
        if (!syncWindowStale) {
            return;
        }
        Window window = evictedSyncWindow;
        for (SpanData span : stored) {
            if (!members.contains(span.spanId())) {
                window = window.including(span);
            }
        }
        syncWindow = window;
        syncWindowStale = false;
    }
}
