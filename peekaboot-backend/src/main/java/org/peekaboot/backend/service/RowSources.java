package org.peekaboot.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.ListedTrace;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Expands a bucket's bundles into listing rows and applies the listing's filters, so
 * {@link TraceInsightsService} stays about turning a page of rows into a response rather than
 * also being about what counts as a row in the first place. Stateless: the store comes from the
 * caller, since nothing here outlives one call.
 */
final class RowSources {

    /** Stable, because an async row clamped to its trace's listedAt ties with it and must list below it. */
    private static final Comparator<RowSource> NEWEST_FIRST =
            Comparator.comparingLong(RowSource::listedAt).reversed();

    private RowSources() {}

    /**
     * One listing row: the bundle it reads from, the span the row is rooted at, whether that span
     * roots a subtree of the trace rather than the trace itself, when the row appeared in the
     * bucket, in span export order, and the bundle's listing facts as the pass read them.
     */
    record RowSource(
            TraceDataBundle bundle, @Nullable SpanData rootSpan, boolean subtree, long listedAt, ListingRoots roots) {

        /** The span an async row is rooted at; null for a whole-trace row. */
        @Nullable
        String subtreeRootSpanId() {
            return subtree ? rootSpan.spanId() : null;
        }

        /** The root's tags for a whole trace; empty for an async row, which answers no request, and while the root is missing. */
        Map<String, String> requestTags() {
            return subtree || rootSpan == null || rootSpan.tags() == null ? Map.of() : rootSpan.tags();
        }

        /** As the row's summary names it: the trace's earliest span, or an async row's own entry span. */
        @Nullable
        Instant startTime() {
            return subtree ? rootSpan.startTime() : roots.startTime();
        }
    }

    /**
     * The bucket's rows that pass {@code filter}, newest first. Nothing is mapped or masked yet but
     * the async rows a verdict filter asks {@code summaryOf} about. Newest means the row appeared in
     * the bucket most recently, which for an async row is when its task ended: the list stays in the
     * order things arrived, and a task that outlived its request by minutes lists at the top rather
     * than beside a request long scrolled away.
     *
     * <p>The bucket's entries are read under the bucket's lock, then each bundle's listing facts under
     * that bundle's lock. A trace that enters, leaves or changes in between is listed as the read of
     * its own facts found it.
     */
    static List<RowSource> matching(
            TraceStore traceStore,
            TraceBucket bucket,
            TraceFilter filter,
            Function<RowSource, TraceSummary> summaryOf) {
        return traceStore.getTraces(bucket, Integer.MAX_VALUE).stream()
                .flatMap(listed -> rowsOf(listed).stream())
                .filter(row -> filter.admits(row, summaryOf))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    /**
     * A bundle's rows: the trace itself, plus one per async entry point it holds, so background
     * work stays findable without knowing which request triggered it. Costs nothing for a
     * bundle with no async spans, which is almost every bundle - and reads the entry spans off
     * the bundle rather than a snapshot, because snapshotting every bundle in a bucket is the
     * cost this pass exists to avoid.
     *
     * <p>The trace's own row is left out while the bundle is a fragment
     * ({@link ListingRoots#fragment()}): listing pieces of a request still running, or of an
     * excluded request whose root is never coming, puts a phantom entry in front of the user
     * that disappears again on its own. An async entry inside a fragment is real work, so it is
     * still listed as a row of its own.
     *
     * <p>An async row is listed from its entry span's export, or from when the trace entered
     * the bucket if that came later: a row cannot have appeared in a bucket before its trace did.
     */
    private static List<RowSource> rowsOf(ListedTrace listed) {
        TraceDataBundle bundle = listed.bundle();
        ListingRoots roots = bundle.listingRoots();
        SpanData root = roots.root();
        List<RowSource> rows = new ArrayList<>();
        if (!roots.fragment()) {
            rows.add(new RowSource(bundle, root, false, listed.listedAt(), roots));
        }
        for (SpanData entry : roots.asyncEntries()) {
            // a purely async bundle's entry span is its trace's own row, added above as a whole
            // trace with no SubtreeView; any other entry, and every entry of a bundle with no
            // root, is a subtree row, which TraceInsightsService maps through
            // TraceTreeMapper.mapSubtree
            if (root != null && entry.spanId().equals(root.spanId())) {
                continue;
            }
            rows.add(new RowSource(bundle, entry, true, Math.max(listed.listedAt(), entry.creationOrder()), roots));
        }
        return rows;
    }
}
