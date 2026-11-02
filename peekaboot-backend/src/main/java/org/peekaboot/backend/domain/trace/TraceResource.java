package org.peekaboot.backend.domain.trace;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceBucket;

/** One trace as the API serves it. {@code data} is null on a listing row asked for with {@code view=summary}. */
public record TraceResource(TraceSummary summary, @Nullable TraceDetail data) {

    /** This trace naming {@code buckets}, in the order {@link TraceBucket} declares them. */
    public TraceResource withBuckets(Set<TraceBucket> buckets) {
        return new TraceResource(summary.withBuckets(buckets.stream().sorted().toList()), data);
    }
}
