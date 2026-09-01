package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * @param filteredBucketCounts the bucket counts after the request's root-action/operation
 *                             filter; null when the request carried no filter
 */
public record TraceInsightsResponse(
        List<TraceTree> traces, BucketCounts bucketCounts, BucketCounts filteredBucketCounts) {}
