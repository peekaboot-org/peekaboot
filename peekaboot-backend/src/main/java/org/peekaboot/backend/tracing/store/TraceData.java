package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * A trace as the store holds it: the spans captured for it and the window they cover.
 *
 * @param spans in creation order, whatever order {@link #fromSpans} was handed them in
 */
public record TraceData(String traceId, Instant startTime, Instant endTime, Duration duration, List<SpanData> spans) {

    public static TraceData fromSpans(String traceId, List<SpanData> spans) {
        if (spans == null || spans.isEmpty()) {
            return new TraceData(traceId, null, null, null, List.of());
        }

        List<SpanData> sortedSpans = spans.stream()
                .sorted(Comparator.comparingLong(SpanData::creationOrder))
                .toList();

        Instant start = sortedSpans.stream()
                .map(SpanData::startTime)
                .filter(t -> t != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        Instant end = sortedSpans.stream()
                .map(SpanData::endTime)
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Duration dur = (start != null && end != null) ? Duration.between(start, end) : null;

        return new TraceData(traceId, start, end, dur, sortedSpans);
    }
}
