package org.peekaboot.backend.domain.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The trace listing.
 *
 * @param filteredBucketCounts the bucket counts after the root-action and operation filters; null only when the
 *     request filtered nothing at all, and a request naming no type still gets the default view's filter
 */
public record TraceListResponse(
        List<TraceResource> traces,
        BucketCounts bucketCounts,
        @Nullable BucketCounts filteredBucketCounts) {}
