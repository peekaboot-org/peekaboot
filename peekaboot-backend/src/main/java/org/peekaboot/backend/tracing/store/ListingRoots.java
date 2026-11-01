package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * The spans a bundle's listing rows start from, and the facts the listing's filters read, read in
 * one go by {@link TraceDataBundle#listingRoots()}.
 *
 * @param root          the span the trace hangs from, the same one {@link TraceData#rootSpan()}
 *                      names; null while the bundle stores neither a span without a parent nor
 *                      one continuing a remote caller's trace
 * @param fragment      spans are stored but the trace is not complete: pieces of a request still
 *                      running, or of one whose root is never coming (an excluded request's)
 * @param asyncEntries  the spans that start an async subtree: a member whose resolved parent is
 *                      not one. Empty for the overwhelming majority of bundles
 * @param startTime     {@link TraceData#startTime()} at the same moment
 * @param duration      {@link TraceData#duration()} at the same moment
 * @param complete      {@link TraceData#complete()} at the same moment
 * @param failed        {@link TraceData#failed()} at the same moment
 */
public record ListingRoots(
        @Nullable SpanData root,
        boolean fragment,
        List<SpanData> asyncEntries,
        @Nullable Instant startTime,
        @Nullable Duration duration,
        boolean complete,
        boolean failed) {

    /** As the trace's summary counts it. Zero while the window is open. */
    public long durationMs() {
        return duration != null ? duration.toMillis() : 0L;
    }
}
