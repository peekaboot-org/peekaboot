package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * A trace as the store holds it, read out in one go by {@link TraceDataBundle#snapshot()}.
 *
 * @param startTime     the earliest span start the bundle ever saw, or null while no timed span
 *                      has arrived
 * @param duration      the window the bundle's non-async spans have covered, or every span's
 *                      while all of them are async; {@link TraceDataBundle#synchronousWindow()}.
 *                      What may lower it is in "Async Subtree Timing" in docs/ARCHITECTURE.md
 * @param rootSpan      the span the tree hangs from: the earliest-created stored span without a
 *                      parent, else the earliest-created one continuing a remote caller's trace;
 *                      null while neither is stored
 * @param complete      a span without a parent has been stored, or one continuing a remote
 *                      caller's trace is; stays true once a span without a parent was stored, so a
 *                      root the span cap evicted later leaves the trace complete, with
 *                      {@code rootSpan} null and every stored span served under the mapped
 *                      trace's {@code orphans}
 * @param spans         in creation order, never null
 * @param asyncSpanIds  the ids of spans belonging to an async subtree - the marked entry spans
 *                      and their descendants. Empty for the overwhelming majority of traces
 * @param truncated     whether the span cap dropped real spans from this trace
 */
public record TraceData(
        String traceId,
        Instant startTime,
        Duration duration,
        @Nullable SpanData rootSpan,
        boolean complete,
        List<SpanData> spans,
        Set<String> asyncSpanIds,
        boolean truncated) {}
