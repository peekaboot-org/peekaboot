package org.peekaboot.backend.tracing.bridge.otel;

import io.micrometer.tracing.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.stacktrace.CapturedStackTraces;
import org.peekaboot.backend.tracing.event.CapturedExceptionInfo;
import org.peekaboot.backend.tracing.event.CapturedText;
import org.peekaboot.backend.tracing.tags.QueryParameterTags;

/**
 * Turns a span of the OpenTelemetry SDK into the
 * {@link org.peekaboot.backend.tracing.event.SpanData} Peekaboot stores.
 */
final class OtelSpanConverter {

    /** The event Micrometer's bridge records a thrown exception as, with the OTel semantic-convention attributes. */
    private static final String EXCEPTION_EVENT = "exception";

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private OtelSpanConverter() {}

    static Map<String, String> tags(SpanData otelSpan) {
        Map<String, String> tags = new HashMap<>();
        otelSpan.getAttributes().forEach((key, value) -> {
            if (value != null) {
                tags.put(key.getKey(), tagValue(key.getKey(), String.valueOf(value)));
            }
        });
        return tags;
    }

    /** The bind parameters pass whole: their writer bounds them, and a cut would break their JSON. */
    private static String tagValue(String key, String value) {
        return QueryParameterTags.PARAMETERS_TAG.equals(key) ? value : CapturedText.cap(value);
    }

    static org.peekaboot.backend.tracing.event.SpanData convert(
            SpanData otelSpan, Map<String, String> tags, long creationOrder) {
        Instant startTime = nanosToInstant(otelSpan.getStartEpochNanos());
        Instant endTime = nanosToInstant(otelSpan.getEndEpochNanos());

        List<org.peekaboot.backend.tracing.event.SpanData.Event> events = extractEvents(otelSpan);

        SpanContext parent = otelSpan.getParentSpanContext();
        String parentId = parent.isValid() ? parent.getSpanId() : null;
        boolean remoteParent = parent.isValid() && parent.isRemote();

        CapturedExceptionInfo exception = capturedException(otelSpan);

        return new org.peekaboot.backend.tracing.event.SpanData(
                otelSpan.getSpanContext().getTraceId(),
                otelSpan.getSpanContext().getSpanId(),
                parentId,
                remoteParent,
                otelSpan.getName(),
                mapKind(otelSpan.getKind()),
                startTime,
                endTime,
                tags,
                events,
                exception,
                creationOrder);
    }

    private static Instant nanosToInstant(long nanos) {
        return Instant.ofEpochSecond(nanos / 1_000_000_000, nanos % 1_000_000_000);
    }

    /**
     * The SDK writes the class, message and full stack trace onto the exception event; the status
     * description only fills in the message of a span that recorded no exception, or one without
     * a message. Only an ERROR status counts: an application may record an exception it handled.
     */
    private static @Nullable CapturedExceptionInfo capturedException(SpanData otelSpan) {
        if (otelSpan.getStatus().getStatusCode() != StatusCode.ERROR) {
            return null;
        }
        String description = otelSpan.getStatus().getDescription();
        String statusMessage = description.isEmpty() ? null : CapturedText.cap(description);
        EventData event = lastExceptionEvent(otelSpan);
        if (event == null) {
            return new CapturedExceptionInfo(null, statusMessage, null);
        }
        Attributes attributes = event.getAttributes();
        String message = attributes.get(EXCEPTION_MESSAGE);
        String stackTrace = attributes.get(EXCEPTION_STACKTRACE);
        return new CapturedExceptionInfo(
                attributes.get(EXCEPTION_TYPE),
                message != null ? CapturedText.cap(message) : statusMessage,
                stackTrace != null ? CapturedStackTraces.capLines(stackTrace) : null);
    }

    private static EventData lastExceptionEvent(SpanData otelSpan) {
        EventData last = null;
        for (EventData event : otelSpan.getEvents()) {
            if (EXCEPTION_EVENT.equals(event.getName())) {
                last = event;
            }
        }
        return last;
    }

    private static List<org.peekaboot.backend.tracing.event.SpanData.Event> extractEvents(SpanData otelSpan) {
        return otelSpan.getEvents().stream()
                .map(OtelSpanConverter::convertEvent)
                .toList();
    }

    private static org.peekaboot.backend.tracing.event.SpanData.Event convertEvent(EventData event) {
        return new org.peekaboot.backend.tracing.event.SpanData.Event(
                event.getName(), nanosToInstant(event.getEpochNanos()));
    }

    private static Span.Kind mapKind(SpanKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case CLIENT -> Span.Kind.CLIENT;
            case SERVER -> Span.Kind.SERVER;
            case PRODUCER -> Span.Kind.PRODUCER;
            case CONSUMER -> Span.Kind.CONSUMER;
            case INTERNAL -> null;
        };
    }
}
