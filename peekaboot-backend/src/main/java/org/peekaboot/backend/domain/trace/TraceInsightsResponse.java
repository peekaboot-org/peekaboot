package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * What the Traces tab reads for one request: the traces that matched it, and the counts
 * the bucket chips show.
 *
 * @param filteredBucketCounts the bucket counts after the request's root-action/operation
 *                             filter; null only when the request filtered nothing at all - a
 *                             request naming no type still gets the default view's filter
 */
public record TraceInsightsResponse(
        List<TraceTree> traces, BucketCounts bucketCounts, BucketCounts filteredBucketCounts) {}
