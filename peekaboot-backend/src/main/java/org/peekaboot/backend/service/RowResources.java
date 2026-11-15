package org.peekaboot.backend.service;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * The rows one listing request maps. A verdict filter judges an async row on its summary, mapped
 * once per request whatever view the request asked for. A summary page serves those mappings, and
 * a full page maps only its own rows in full, so no row is laid out for a page that leaves it out.
 * Lives for one request; not thread-safe.
 */
final class RowResources {

    private final TraceStore traceStore;
    private final TraceResources traceResources;
    private final TraceView view;
    private final Map<RowKey, TraceResource> summaries = new HashMap<>();

    RowResources(TraceStore traceStore, TraceResources traceResources, TraceView view) {
        this.traceStore = traceStore;
        this.traceResources = traceResources;
        this.view = view;
    }

    /**
     * The row in the requested view, for the page. A full row is a later read than the summary a
     * filter judged it on, so a span that landed in between shows in the row alone.
     */
    TraceResource of(RowSource row) {
        return view == TraceView.SUMMARY ? summaryRow(row) : map(row, view);
    }

    TraceSummary summaryOf(RowSource row) {
        return summaryRow(row).summary();
    }

    private TraceResource summaryRow(RowSource row) {
        return summaries.computeIfAbsent(
                new RowKey(row.bundle().traceId(), row.subtreeRootSpanId()), key -> map(row, TraceView.SUMMARY));
    }

    private TraceResource map(RowSource row, TraceView rowView) {
        return TraceReads.row(traceStore, traceResources, row.bundle(), row.subtreeRootSpanId(), rowView);
    }

    /**
     * The request path as the row's summary serves it, masked, read without mapping the row. Null
     * for an async row, which answers no request, and for a root that names no path.
     */
    @Nullable
    String pathOf(RowSource row) {
        if (row.subtree()) {
            return null;
        }
        TraceSummary.Request request = traceResources.requestOf(row.rootSpan());
        return request == null ? null : request.path();
    }

    private record RowKey(String traceId, @Nullable String subtreeRootSpanId) {}
}
