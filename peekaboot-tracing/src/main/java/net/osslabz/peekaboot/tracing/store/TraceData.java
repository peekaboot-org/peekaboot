package net.osslabz.peekaboot.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record TraceData(
        String traceId,
        Instant startTime,
        Instant endTime,
        Duration duration,
        int spanCount,
        List<SpanData> spans
) {

    public static TraceData fromSpans(String traceId, List<SpanData> spans) {
        if (spans == null || spans.isEmpty()) {
            return new TraceData(traceId, null, null, null, 0, List.of());
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

        return new TraceData(traceId, start, end, dur, sortedSpans.size(), sortedSpans);
    }

    public boolean isComplete() {
        return spans != null && spans.stream().allMatch(SpanData::isComplete);
    }

    public boolean hasErrors() {
        return spans != null && spans.stream().anyMatch(SpanData::hasError);
    }
}
