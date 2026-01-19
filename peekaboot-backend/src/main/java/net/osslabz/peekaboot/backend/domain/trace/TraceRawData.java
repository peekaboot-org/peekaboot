package net.osslabz.peekaboot.backend.domain.trace;

import net.osslabz.peekaboot.backend.tracing.store.SpanData;

import java.time.Instant;
import java.util.List;

/**
 * Raw trace data for a single trace.
 */
public record TraceRawData(
    CollectionFramework collectionFramework,
    String traceId,
    Instant startTime,
    Instant endTime,
    long durationMs,
    PerTraceSummary summary,
    List<SpanData> spans,
    List<TraceLog> logs,
    List<QueryInfo> queries,
    HttpExchange httpExchange
) {
    public record PerTraceSummary(
        TraceRawSummary.CountDuration spans,
        TraceRawSummary.CountDuration queries,
        TraceRawSummary.Count logs,
        TraceRawSummary.Count errors
    ) {
        public static PerTraceSummary empty() {
            return new PerTraceSummary(
                new TraceRawSummary.CountDuration(0, 0),
                new TraceRawSummary.CountDuration(0, 0),
                new TraceRawSummary.Count(0),
                new TraceRawSummary.Count(0)
            );
        }
    }
}
