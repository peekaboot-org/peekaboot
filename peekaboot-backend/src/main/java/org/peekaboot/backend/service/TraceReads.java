package org.peekaboot.backend.service;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Maps a stored trace and reads its buckets afterwards. The store files a trace in its buckets in
 * the same step under the bundle's span lock that stores the span, so buckets read after the
 * mapping include every bucket the mapped spans put the trace in. Read the other way round, one
 * response could say complete, ERROR and no bucket.
 *
 * <p>The mapping reads the bundle's spans and its logs under one lock each; the buckets are read
 * after both, one at a time. A span or ERROR line that arrives in that window can name a bucket
 * the mapped data does not explain yet, an async entry can take the trace out of Slow, and a full
 * bucket can evict it. The next read agrees.
 */
final class TraceReads {

    private TraceReads() {}

    static TraceResource whole(TraceStore store, TraceResources resources, TraceDataBundle bundle) {
        // mapped without buckets: a summary only echoes them, nothing is judged from them
        return resources.trace(bundle, Set.of()).withBuckets(store.bucketsOf(bundle.traceId()));
    }

    static TraceResource row(
            TraceStore store,
            TraceResources resources,
            TraceDataBundle bundle,
            @Nullable String subtreeRootSpanId,
            TraceView view) {
        return resources.row(bundle, subtreeRootSpanId, Set.of(), view).withBuckets(store.bucketsOf(bundle.traceId()));
    }
}
