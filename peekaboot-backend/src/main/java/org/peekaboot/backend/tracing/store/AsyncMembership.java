package org.peekaboot.backend.tracing.store;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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

    /**
     * A span that enters a subtree as it arrives, so it never counted as synchronous. The
     * duplicates it absorbed on arrival did, and their time leaves with it.
     */
    void enter(String spanId, List<SpanData> absorbedDuplicates) {
        members.add(spanId);
        absorbedDuplicates.forEach(this::leaveSyncWindow);
    }

    /** A stored span joining a subtree; false if it already belonged to one. */
    boolean join(SpanData span) {
        if (!members.add(span.spanId())) {
            return false;
        }
        leaveSyncWindow(span);
        return true;
    }

    private void leaveSyncWindow(SpanData span) {
        if (syncWindow.isBoundedBy(span)) {
            syncWindowStale = true;
        }
    }

    /**
     * A duplicate folded into its survivor, which lies around it. A survivor that stays
     * synchronous covers its time; one that enters a subtree takes it along (see {@link #enter}).
     */
    void forget(SpanData span) {
        members.remove(span.spanId());
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
