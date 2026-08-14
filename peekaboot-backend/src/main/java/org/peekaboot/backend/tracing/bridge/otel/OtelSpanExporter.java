package org.peekaboot.backend.tracing.bridge.otel;

import io.micrometer.tracing.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.store.TraceStore;

import org.peekaboot.backend.filter.FilterPathMatcher;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenTelemetry SpanExporter that captures spans and publishes them via Spring events.
 */
public class OtelSpanExporter implements SpanExporter {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> HTTP_URL_KEY = AttributeKey.stringKey("http.url");
    private static final AttributeKey<String> HTTP_TARGET_KEY = AttributeKey.stringKey("http.target");
    private static final AttributeKey<String> URL_PATH_KEY = AttributeKey.stringKey("url.path");

    private final TraceStore storage;
    private final ApplicationEventPublisher eventPublisher;

    public OtelSpanExporter(TraceStore storage, ApplicationEventPublisher eventPublisher) {
        this.storage = storage;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData otelSpan : spans) {
            if (shouldSkipSpan(otelSpan)) {
                continue;
            }
            org.peekaboot.backend.tracing.store.SpanData spanData = convertToSpanData(otelSpan);
            eventPublisher.publishEvent(new SpanDataEvent(spanData));
        }
        return CompletableResultCode.ofSuccess();
    }

    private boolean shouldSkipSpan(SpanData span) {
        String path = extractPath(span);
        if (path != null && FilterPathMatcher.shouldSkip(path)) {
            return true;
        }
        String name = span.getName();
        if (name != null && name.contains("/peekaboot/")) {
            return true;
        }
        return false;
    }

    private String extractPath(SpanData span) {
        String urlPath = span.getAttributes().get(URL_PATH_KEY);
        if (urlPath != null) {
            return urlPath;
        }
        String httpTarget = span.getAttributes().get(HTTP_TARGET_KEY);
        if (httpTarget != null) {
            return httpTarget;
        }
        String httpUrl = span.getAttributes().get(HTTP_URL_KEY);
        if (httpUrl != null) {
            int pathStart = httpUrl.indexOf('/', httpUrl.indexOf("://") + 3);
            if (pathStart > 0) {
                int queryStart = httpUrl.indexOf('?', pathStart);
                return queryStart > 0 ? httpUrl.substring(pathStart, queryStart) : httpUrl.substring(pathStart);
            }
        }
        return null;
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private org.peekaboot.backend.tracing.store.SpanData convertToSpanData(SpanData otelSpan) {
        Instant startTime = nanosToInstant(otelSpan.getStartEpochNanos());
        Instant endTime = nanosToInstant(otelSpan.getEndEpochNanos());
        Duration duration = Duration.between(startTime, endTime);

        Map<String, String> tags = extractAttributes(otelSpan);
        List<org.peekaboot.backend.tracing.store.SpanData.Event> events = extractEvents(otelSpan);

        String parentId = otelSpan.getParentSpanContext().isValid()
                ? otelSpan.getParentSpanContext().getSpanId()
                : null;

        String errorMessage = null;
        String errorClass = null;
        if (otelSpan.getStatus().getStatusCode() == StatusCode.ERROR) {
            errorMessage = otelSpan.getStatus().getDescription();
            errorClass = "ERROR";
        }

        String serviceName = extractServiceName(otelSpan);

        return new org.peekaboot.backend.tracing.store.SpanData(
                otelSpan.getSpanContext().getTraceId(),
                otelSpan.getSpanContext().getSpanId(),
                parentId,
                otelSpan.getName(),
                mapKind(otelSpan.getKind()),
                startTime,
                endTime,
                duration,
                tags,
                events,
                errorMessage,
                errorClass,
                serviceName,
                null,
                null,
                List.of(),
                storage.nextCreationOrder()
        );
    }

    private Instant nanosToInstant(long nanos) {
        return Instant.ofEpochSecond(nanos / 1_000_000_000, nanos % 1_000_000_000);
    }

    private Map<String, String> extractAttributes(SpanData otelSpan) {
        Map<String, String> tags = new HashMap<>();
        otelSpan.getAttributes().forEach((key, value) -> {
            if (value != null) {
                tags.put(key.getKey(), String.valueOf(value));
            }
        });
        return tags;
    }

    private List<org.peekaboot.backend.tracing.store.SpanData.Event> extractEvents(SpanData otelSpan) {
        return otelSpan.getEvents().stream()
                .map(this::convertEvent)
                .toList();
    }

    private org.peekaboot.backend.tracing.store.SpanData.Event convertEvent(EventData event) {
        return new org.peekaboot.backend.tracing.store.SpanData.Event(
                event.getName(),
                nanosToInstant(event.getEpochNanos())
        );
    }

    private String extractServiceName(SpanData otelSpan) {
        Object serviceName = otelSpan.getResource().getAttribute(SERVICE_NAME_KEY);
        return serviceName != null ? String.valueOf(serviceName) : null;
    }

    private Span.Kind mapKind(SpanKind kind) {
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
