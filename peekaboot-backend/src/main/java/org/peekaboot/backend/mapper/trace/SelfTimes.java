package org.peekaboot.backend.mapper.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.peekaboot.backend.tracing.event.SpanData;

/** How long a span worked itself while no child span ran, the time its SLOW and VERY_SLOW issues judge. */
final class SelfTimes {

    private record Interval(long fromNanos, long toNanos) {}

    private SelfTimes() {}

    /**
     * Counted in nanoseconds and cut to milliseconds once, so sub-millisecond gaps add up as they
     * do in the duration. Zero for a span without both ends, whose duration is unknown too.
     */
    static long selfMs(SpanData span, List<SpanData> children) {
        Instant start = span.startTime();
        Instant end = span.endTime();
        if (start == null || end == null || !end.isAfter(start)) {
            return 0L;
        }
        long totalNanos = Duration.between(start, end).toNanos();
        List<Interval> covered = new ArrayList<>();
        for (SpanData child : children) {
            if (child.startTime() == null || child.endTime() == null) {
                continue;
            }
            long from = Math.max(0L, Duration.between(start, child.startTime()).toNanos());
            long to = Math.min(
                    totalNanos, Duration.between(start, child.endTime()).toNanos());
            covered.add(new Interval(from, to));
        }
        covered.sort(Comparator.comparingLong(Interval::fromNanos));
        long coveredNanos = 0L;
        long reach = 0L;
        for (Interval interval : covered) {
            long from = Math.max(interval.fromNanos(), reach);
            if (interval.toNanos() > from) {
                coveredNanos += interval.toNanos() - from;
                reach = interval.toNanos();
            }
        }
        return Duration.ofNanos(totalNanos - coveredNanos).toMillis();
    }
}
