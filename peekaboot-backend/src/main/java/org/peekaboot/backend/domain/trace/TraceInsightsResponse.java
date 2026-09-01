package org.peekaboot.backend.domain.trace;

import java.util.List;

public record TraceInsightsResponse(
        List<TraceTree> traces,
        TraceListSummary summary,
        BucketCounts bucketCounts,
        BucketCounts filteredBucketCounts) {

    public TraceInsightsResponse(List<TraceTree> traces, TraceListSummary summary, BucketCounts bucketCounts) {
        this(traces, summary, bucketCounts, null);
    }
}
