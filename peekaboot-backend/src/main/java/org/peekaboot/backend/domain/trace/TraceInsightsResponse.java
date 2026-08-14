package org.peekaboot.backend.domain.trace;

import java.util.List;

public record TraceInsightsResponse(
    List<TraceTree> traces,
    TraceListSummary summary,
    BucketCounts bucketCounts
) {}
