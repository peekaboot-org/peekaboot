package org.peekaboot.backend.domain.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The trace listing.
 *
 * @param filteredBucketCounts the bucket counts after every filter; null only when the request
 *     filtered nothing at all, and a request naming no type still gets the default view's filter
 * @param next the cursor for the page after this one; null on the last page
 */
public record TraceListResponse(
        List<TraceResource> traces,
        BucketCounts bucketCounts,
        @Nullable BucketCounts filteredBucketCounts,
        @Nullable String next) {}
