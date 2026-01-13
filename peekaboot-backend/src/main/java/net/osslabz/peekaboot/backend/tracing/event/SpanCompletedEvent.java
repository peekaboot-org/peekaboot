package net.osslabz.peekaboot.backend.tracing.event;

import io.micrometer.tracing.Span;

import java.util.Map;

public record SpanCompletedEvent(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        Span.Kind kind,
        long startTimeMs,
        long durationMs,
        Map<String, String> attributes,
        String errorMessage,
        String errorClass
) implements TraceDataEvent {
}
