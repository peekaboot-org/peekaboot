package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A trace as the store holds it, read out in one go by {@link TraceDataBundle#snapshot()}.
 *
 * @param startTime     the earliest span start the bundle ever saw, or null while no timed span
 *                      has arrived
 * @param duration      the window the bundle's non-async spans have covered, high-water like
 *                      {@code startTime}: the same number the Slow bucket admitted the trace by,
 *                      even once the span cap has evicted the earliest spans
 * @param rootSpan      the span the tree hangs from, chosen by the bundle (see
 *                      {@link TraceDataBundle#rootSpan()}); null for a trace with no spans
 * @param spans         in creation order, never null
 * @param asyncSpanIds  the ids of spans belonging to an async subtree - the marked entry spans
 *                      and their descendants. Empty for the overwhelming majority of traces
 * @param truncated     whether the span cap dropped real spans from this trace
 */
public record TraceData(
        String traceId,
        Instant startTime,
        Duration duration,
        SpanData rootSpan,
        List<SpanData> spans,
        Set<String> asyncSpanIds,
        boolean truncated) {}
