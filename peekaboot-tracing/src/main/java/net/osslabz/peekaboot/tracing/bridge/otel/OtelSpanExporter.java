package net.osslabz.peekaboot.tracing.bridge.otel;

import io.micrometer.tracing.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OtelSpanExporter implements SpanExporter {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");

    private final InMemorySpanStore store;

    public OtelSpanExporter(InMemorySpanStore store) {
        this.store = store;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData otelSpan : spans) {
            net.osslabz.peekaboot.tracing.store.SpanData spanData = convertToSpanData(otelSpan);
            store.report(spanData);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private net.osslabz.peekaboot.tracing.store.SpanData convertToSpanData(SpanData otelSpan) {
        Instant startTime = nanosToInstant(otelSpan.getStartEpochNanos());
        Instant endTime = nanosToInstant(otelSpan.getEndEpochNanos());
        Duration duration = Duration.between(startTime, endTime);

        Map<String, String> tags = extractAttributes(otelSpan);
        List<net.osslabz.peekaboot.tracing.store.SpanData.Event> events = extractEvents(otelSpan);

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

        return new net.osslabz.peekaboot.tracing.store.SpanData(
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
                store.nextCreationOrder()
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

    private List<net.osslabz.peekaboot.tracing.store.SpanData.Event> extractEvents(SpanData otelSpan) {
        return otelSpan.getEvents().stream()
                .map(this::convertEvent)
                .toList();
    }

    private net.osslabz.peekaboot.tracing.store.SpanData.Event convertEvent(EventData event) {
        return new net.osslabz.peekaboot.tracing.store.SpanData.Event(
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
