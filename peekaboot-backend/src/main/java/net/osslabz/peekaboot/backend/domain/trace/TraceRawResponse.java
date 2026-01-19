package net.osslabz.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * Response for GET /api/traces/raw endpoint.
 */
public record TraceRawResponse(
    CollectionFramework collectionFramework,
    TraceRawSummary summary,
    List<TraceRawData> traces
) {
    public static TraceRawResponse empty(CollectionFramework framework) {
        return new TraceRawResponse(framework, TraceRawSummary.empty(), List.of());
    }
}
