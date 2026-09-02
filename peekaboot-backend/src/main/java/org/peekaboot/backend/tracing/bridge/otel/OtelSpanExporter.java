package org.peekaboot.backend.tracing.bridge.otel;

import io.micrometer.tracing.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.mapper.trace.HttpSpanTags;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Copies every span the host's OpenTelemetry SDK exports into Peekaboot's own store, as a
 * {@link SpanDataEvent} per span, leaving out Peekaboot's own requests. Runs beside
 * whatever other exporter the application has configured, never instead of it.
 */
public class OtelSpanExporter implements SpanExporter {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");

    private final ApplicationEventPublisher eventPublisher;
    private final PeekabootPaths paths;

    /** Numbers spans in export order; per-trace sorting only ever compares orders minted here. */
    private final AtomicLong creationOrder = new AtomicLong();

    public OtelSpanExporter(ApplicationEventPublisher eventPublisher, PeekabootPaths paths) {
        this.eventPublisher = eventPublisher;
        this.paths = paths;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData otelSpan : spans) {
            Map<String, String> tags = extractAttributes(otelSpan);
            if (shouldSkipSpan(otelSpan, tags)) {
                // A skipped root ends a trace that is Peekaboot's own. Its children exported
                // before it and its logs and request arrived synchronously, so whatever the
                // trace stored is complete and one discard clears it.
                if (!otelSpan.getParentSpanContext().isValid()) {
                    eventPublisher.publishEvent(
                            new TraceDiscardedEvent(otelSpan.getSpanContext().getTraceId()));
                }
                continue;
            }
            org.peekaboot.backend.tracing.store.SpanData spanData = convertToSpanData(otelSpan, tags);
            eventPublisher.publishEvent(new SpanDataEvent(spanData));
        }
        return CompletableResultCode.ofSuccess();
    }

    /**
     * Peekaboot's own requests, recognised by the span's HTTP path or, failing that, its
     * name. The path tag carries the servlet context path while the name (Spring's matched
     * route pattern) does not, so the path goes through the context-stripping check.
     * Judged per span: the children of such a request carry neither and are stored, which
     * is what the discard in {@link #export} undoes once their root arrives.
     */
    private boolean shouldSkipSpan(SpanData span, Map<String, String> tags) {
        String path = HttpSpanTags.path(tags);
        if (path != null && paths.isExcludedRequestPath(path)) {
            return true;
        }
        String name = span.getName();
        return name != null && name.contains(PeekabootPaths.BASE_PATH + "/");
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private org.peekaboot.backend.tracing.store.SpanData convertToSpanData(
            SpanData otelSpan, Map<String, String> tags) {
        Instant startTime = nanosToInstant(otelSpan.getStartEpochNanos());
        Instant endTime = nanosToInstant(otelSpan.getEndEpochNanos());
        Duration duration = Duration.between(startTime, endTime);

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
                creationOrder.incrementAndGet());
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
        return otelSpan.getEvents().stream().map(this::convertEvent).toList();
    }

    private org.peekaboot.backend.tracing.store.SpanData.Event convertEvent(EventData event) {
        return new org.peekaboot.backend.tracing.store.SpanData.Event(
                event.getName(), nanosToInstant(event.getEpochNanos()));
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
