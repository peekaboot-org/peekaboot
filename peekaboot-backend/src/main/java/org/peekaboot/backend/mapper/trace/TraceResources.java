package org.peekaboot.backend.mapper.trace;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/**
 * Turns a stored bundle into the trace the API serves, whole or as one listing row. Every mapper is
 * built here rather than injected, as in {@code ActuatorInsightsService}, so this is the one place
 * the masking policy enters the trace pipeline.
 */
public final class TraceResources {

    private final TraceRequests traceRequests;
    private final TraceRows traceRows;

    public TraceResources(
            MaskingEngine maskingEngine,
            SqlFormatter sqlFormatter,
            PeekabootTracingProperties tracingProperties,
            StackTraceRules stackTraceRules) {
        this.traceRequests = new TraceRequests(maskingEngine);
        this.traceRows = new TraceRows(
                new TraceTreeMapper(maskingEngine, sqlFormatter, stackTraceRules, traceRequests),
                new IssueDetector(tracingProperties),
                new TraceLogMapper(maskingEngine, stackTraceRules),
                new TraceSummaries(tracingProperties),
                new TraceDetails(new HttpExchangeMapper(maskingEngine)));
    }

    /** The request a root span answered as a summary names it, without mapping its trace. */
    public TraceSummary.@Nullable Request requestOf(@Nullable SpanData root) {
        return traceRequests.of(root);
    }

    /** The whole trace, its summary and its data. */
    public TraceResource trace(TraceDataBundle bundle, Set<TraceBucket> buckets) {
        return row(bundle, null, buckets, TraceView.FULL);
    }

    /**
     * One listing row, the whole trace or the async subtree {@code subtreeRootSpanId} names. A subtree
     * whose entry span was evicted since the listing maps as the whole trace.
     */
    public TraceResource row(
            TraceDataBundle bundle, @Nullable String subtreeRootSpanId, Set<TraceBucket> buckets, TraceView view) {
        return traceRows.of(bundle, subtreeRootSpanId, buckets, view);
    }
}
