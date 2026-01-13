package net.osslabz.peekaboot.backend.tracing.store;

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
        String remoteIp,
        Integer remotePort,
        List<LinkData> links,
        long creationOrder
) {

    public record Event(String name, Instant timestamp) {}

    public record LinkData(String traceId, String spanId, Map<String, Object> tags) {}

    public boolean hasError() {
        return errorMessage != null || errorClass != null;
    }

    public boolean isComplete() {
        return endTime != null;
    }
}
