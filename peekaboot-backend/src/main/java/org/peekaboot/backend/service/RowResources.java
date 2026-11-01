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
 * The rows one listing request maps, each at most once. An async row a verdict filter read is
 * mapped in the requested view, so the page serves that same mapping instead of a second one.
 * Lives for one request; not thread-safe.
 */
final class RowResources {

    private final TraceStore traceStore;
    private final TraceResources traceResources;
    private final TraceView view;
    private final Map<RowKey, TraceResource> mapped = new HashMap<>();

    RowResources(TraceStore traceStore, TraceResources traceResources, TraceView view) {
        this.traceStore = traceStore;
        this.traceResources = traceResources;
        this.view = view;
    }

    TraceResource of(RowSource row) {
        return mapped.computeIfAbsent(
                new RowKey(row.bundle().traceId(), row.subtreeRootSpanId()),
                key -> traceResources.row(
                        row.bundle(), key.subtreeRootSpanId(), traceStore.bucketsOf(key.traceId()), view));
    }

    TraceSummary summaryOf(RowSource row) {
        return of(row).summary();
    }

    private record RowKey(String traceId, @Nullable String subtreeRootSpanId) {}
}
