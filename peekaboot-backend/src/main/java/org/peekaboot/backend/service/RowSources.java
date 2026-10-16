package org.peekaboot.backend.service;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.tracing.store.ListedTrace;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Expands a bucket's bundles into listing rows and applies the root-level filters, so
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
     * roots a subtree of the trace rather than the trace itself, and when the row appeared in the
     * bucket, in span export order.
     */
    record RowSource(TraceDataBundle bundle, SpanData rootSpan, boolean subtree, long listedAt) {}

    /**
     * The bucket's rows that pass the root-level filters, newest first - nothing mapped or masked
     * yet. Newest means the row appeared in the bucket most recently, which for an async row is
     * when its task ended: the list stays in the order things arrived, and a task that outlived
     * its request by minutes lists at the top rather than beside a request long scrolled away.
     */
    static List<RowSource> matching(
            TraceStore traceStore, TraceBucket bucket, Set<RootActionType> actionTypes, String rootOperation) {
        return traceStore.getTraces(bucket, Integer.MAX_VALUE).stream()
                .flatMap(listed -> rowsOf(listed).stream())
                .filter(row -> matchesRootFilters(row.rootSpan(), actionTypes, rootOperation))
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
     * <p>The trace's own row is skipped when its root is an incomplete fragment - see
     * {@link #isIncompleteFragment} - but that skip applies only to the trace's own row: an
     * async entry inside a fragment bundle is still real work, so the bundle can still
     * contribute rows even when its own root is filtered out.
     *
     * <p>An async row is listed from its entry span's export, or from when the trace entered
     * the bucket if that came later: a row cannot have appeared in a bucket before its trace did.
     */
    private static List<RowSource> rowsOf(ListedTrace listed) {
        TraceDataBundle bundle = listed.bundle();
        ListingRoots roots = bundle.listingRoots();
        SpanData root = roots.root();
        List<RowSource> rows = new ArrayList<>();
        if (!isIncompleteFragment(root)) {
            rows.add(new RowSource(bundle, root, false, listed.listedAt()));
        }
        for (SpanData entry : roots.asyncEntries()) {
            // a purely async bundle's entry span is its trace's own row, added above as a whole
            // trace with no SubtreeView; any other entry is a subtree row, which
            // TraceInsightsService maps through TraceTreeMapper.mapSubtree
            if (root != null && entry.spanId().equals(root.spanId())) {
                continue;
            }
            rows.add(new RowSource(bundle, entry, true, Math.max(listed.listedAt(), entry.creationOrder())));
        }
        return rows;
    }

    /**
     * The two root-level filters, applied to a row's own root span. Action type and
     * operation come straight off {@link TraceTreeMapper}'s root-span logic, so the verdict
     * matches what mapping the full tree would say - without copying, building or masking a
     * tree for rows the response will never carry. An empty type set and a null operation
     * each filter nothing away, and short-circuit before the classification a wildcard has
     * no use for.
     */
    private static boolean matchesRootFilters(SpanData root, Set<RootActionType> actionTypes, String rootOperation) {
        return (actionTypes.isEmpty() || actionTypes.contains(TraceTreeMapper.detectRootActionType(root)))
                && (rootOperation == null || matchesRootOperation(root != null ? root.name() : null, rootOperation));
    }

    /**
     * Whether the bundle holds a piece of a trace rather than a trace of its own.
     * {@link TraceDataBundle#listingRoots()} answers with the first span whose parent it does
     * not hold, so a root still carrying a parent id is one whose real parent never
     * arrived.
     *
     * <p>For a SERVER root that is an inbound request continuing a caller's trace: this
     * application's work starts there and the trace is its own, so it stays listed. For
     * anything else it is a fragment - a request on an excluded prefix (Peekaboot's own,
     * the actuator's) has its root span skipped, which leaves whatever it did meanwhile,
     * typically the connection it acquired, stored alone under that trace id until the
     * skipped root reaches the exporter and discards it. Listing it in the meantime puts a
     * phantom entry in front of the user that disappears again on its own.
     *
     * <p>An async-marked span whose parent never arrived is exempted: it is work that really
     * ran, not somebody else's trace seen through a hole, so it is listed rather than hidden.
     */
    private static boolean isIncompleteFragment(SpanData root) {
        return root != null
                && root.parentId() != null
                && root.kind() != Span.Kind.SERVER
                && !AsyncTaskMarker.isMarked(root.tags());
    }

    /** The chip sends a substring of the operation name, so a partial, case-insensitive match is the contract. */
    private static boolean matchesRootOperation(String rootOperationName, String rootOperation) {
        if (rootOperationName == null) {
            return false;
        }
        String operation = rootOperationName.toLowerCase(Locale.ROOT);
        String filter = rootOperation.toLowerCase(Locale.ROOT);
        if (operation.contains(filter)) {
            return true;
        }
        // Scheduled task targets are fully qualified (package.Class.method) while span
        // names use the bean name ("task class.method") - also match on the Class.method suffix
        String[] segments = filter.split("\\.", -1);
        return segments.length > 2
                && operation.contains(segments[segments.length - 2] + "." + segments[segments.length - 1]);
    }
}
