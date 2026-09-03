package org.peekaboot.backend.tracing.store;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SpanData(
        String traceId,
        String spanId,
        String parentId,
        String name,
        Span.Kind kind,
        Instant startTime,
        Instant endTime,
        Duration duration,
        Map<String, String> tags,
        List<Event> events,
        String errorMessage,
        String errorClass,
        String remoteServiceName,
        long creationOrder) {

    public record Event(String name, Instant timestamp) {}

    public boolean hasError() {
        return errorMessage != null || errorClass != null;
    }

    /** Returns a copy of this span re-parented to {@code newParentId}. */
    public SpanData withParentId(String newParentId) {
        return new SpanData(
                traceId,
                spanId,
                newParentId,
                name,
                kind,
                startTime,
                endTime,
                duration,
                tags,
                events,
                errorMessage,
                errorClass,
                remoteServiceName,
                creationOrder);
    }
}
