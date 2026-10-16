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
 * @param duration      the window the bundle's non-async spans have covered, or every span's
 *                      while all of them are async; {@link TraceDataBundle#synchronousWindow()}.
 *                      What may lower it is in "Async Subtree Timing" in docs/ARCHITECTURE.md
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
