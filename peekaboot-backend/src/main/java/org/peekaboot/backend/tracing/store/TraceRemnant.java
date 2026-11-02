package org.peekaboot.backend.tracing.store;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the inputs a fold did not write left in a trace's bundle: the counts, and the facts those
 * inputs set, which the kept inputs may not set again. A fold writes the kept inputs and this, so a
 * restore shows the trace as the store held it. It lists the trace in no bucket; the snapshot's bucket
 * lines do (see {@code JournalLine.BucketLine}).
 *
 * @param droppedSpans         spans the span cap evicted
 * @param droppedLogs          logs the log cap trimmed
 * @param parentlessSpanStored a span without a parent was stored, maybe one evicted since; see {@link TraceData#complete}
 * @param failed               {@link TraceData#failed}, which an evicted span or a trimmed ERROR line can have set
 * @param start                the earliest start of any span the bundle stored
 * @param end                  the latest end of any span the bundle stored
 * @param evictedSyncStart     the earliest start of a synchronous span the cap evicted
 * @param evictedSyncEnd       the latest end of one
 * @param asyncSpanIds         the stored spans in an async subtree, sorted; one whose entry span the cap
 *                             evicted would otherwise arrive synchronous
 */
public record TraceRemnant(
        int droppedSpans,
        int droppedLogs,
        boolean parentlessSpanStored,
        boolean failed,
        @Nullable Instant start,
        @Nullable Instant end,
        @Nullable Instant evictedSyncStart,
        @Nullable Instant evictedSyncEnd,
        List<String> asyncSpanIds) {

    public TraceRemnant {
        asyncSpanIds = List.copyOf(requireNonNull(asyncSpanIds, "asyncSpanIds"));
    }

    /** Whether the caps dropped anything; a fold writes a remnant line then, or where the trace's first line is not kept. */
    public boolean droppedAnything() {
        return droppedSpans > 0 || droppedLogs > 0;
    }
}
