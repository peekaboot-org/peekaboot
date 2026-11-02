package org.peekaboot.backend.tracing.event;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A finished span, as {@code OtelSpanExporter} converts it from the SDK's.
 *
 * @param remoteParent the parent span context came from another process, so it will never be
 *                     exported here unless this application called itself
 */
public record SpanData(
        String traceId,
        String spanId,
        String parentId,
        boolean remoteParent,
        String name,
        Span.Kind kind,
        Instant startTime,
        Instant endTime,
        Map<String, String> tags,
        List<Event> events,
        @Nullable CapturedExceptionInfo exception,
        long creationOrder) {

    public record Event(String name, Instant timestamp) {}

    public boolean hasError() {
        return exception != null;
    }

    /** Null while either end is unknown. */
    public Duration duration() {
        return startTime == null || endTime == null ? null : Duration.between(startTime, endTime);
    }

    /** Zero for a span whose duration was never recorded. */
    public long durationMs() {
        Duration duration = duration();
        return duration != null ? duration.toMillis() : 0L;
    }

    /** Returns a copy of this span re-parented to {@code newParentId}. */
    public SpanData withParentId(String newParentId) {
        return new SpanData(
                traceId,
                spanId,
                newParentId,
                remoteParent,
                name,
                kind,
                startTime,
                endTime,
                tags,
                events,
                exception,
                creationOrder);
    }

    /**
     * This span with its creation order raised by {@code base}, so it sorts after every span numbered up
     * to {@code base}; itself where {@code base} is 0.
     */
    public SpanData numberedAfter(long base) {
        if (base == 0) {
            return this;
        }
        return new SpanData(
                traceId,
                spanId,
                parentId,
                remoteParent,
                name,
                kind,
                startTime,
                endTime,
                tags,
                events,
                exception,
                creationOrder + base);
    }
}
