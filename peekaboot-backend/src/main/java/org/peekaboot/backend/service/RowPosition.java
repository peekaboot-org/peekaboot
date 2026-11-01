package org.peekaboot.backend.service;

import java.util.Comparator;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.ListedTrace;

/**
 * Where a listing row sits. Newest first is the later {@code listedAt}, then the later bucket entry,
 * then a trace's own row ahead of its async rows in the order their tasks ended. No component changes
 * while the row stays in its bucket, which is what lets a cursor name the row.
 *
 * @param listedAt the row's export-order stamp: its trace's {@link ListedTrace#listedAt()}, or an async
 *     row's entry span's {@link SpanData#creationOrder()} where that is later
 * @param listing the bucket entry's {@link ListedTrace#sequence()}
 * @param entry {@link #WHOLE_TRACE} for a trace's own row, else the entry span's creation order
 */
record RowPosition(long listedAt, long listing, long entry) {

    /** Below every creation order, so a trace's own row sorts ahead of its async rows. */
    static final long WHOLE_TRACE = -1;

    static final Comparator<RowPosition> NEWEST_FIRST = Comparator.comparingLong(RowPosition::listedAt)
            .thenComparingLong(RowPosition::listing)
            .reversed()
            .thenComparingLong(RowPosition::entry);

    static RowPosition ofTrace(ListedTrace listed) {
        return new RowPosition(listed.listedAt(), listed.sequence(), WHOLE_TRACE);
    }

    static RowPosition ofAsyncEntry(ListedTrace listed, SpanData entry) {
        return new RowPosition(
                Math.max(listed.listedAt(), entry.creationOrder()), listed.sequence(), entry.creationOrder());
    }

    /** Whether this row lists below {@code other}, newest first: the rows a page after {@code other} holds. */
    boolean listsAfter(RowPosition other) {
        return NEWEST_FIRST.compare(this, other) > 0;
    }
}
