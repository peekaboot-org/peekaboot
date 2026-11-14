package org.peekaboot.backend.tracing.bridge.otel;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Copies every span the host's OpenTelemetry SDK exports into Peekaboot's own store, as a
 * {@link SpanDataEvent} per span, leaving out requests on the excluded prefixes. Runs beside
 * whatever other exporter the application has configured, never instead of it.
 */
public class OtelSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(OtelSpanExporter.class);

    private final ApplicationEventPublisher eventPublisher;
    private final PeekabootPaths paths;

    /** Numbers spans in export order; per-trace sorting only ever compares orders minted here. */
    private final AtomicLong creationOrder = new AtomicLong();

    private final AtomicBoolean warned = new AtomicBoolean();

    public OtelSpanExporter(ApplicationEventPublisher eventPublisher, PeekabootPaths paths) {
        this.eventPublisher = eventPublisher;
        this.paths = paths;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData otelSpan : spans) {
            try {
                Map<String, String> tags = OtelSpanConverter.tags(otelSpan);
                if (shouldSkipSpan(otelSpan, tags)) {
                    // A skipped span ends an excluded request. The discard assumes nothing more
                    // arrives for that request: the excluded prefixes serve no endpoint that
                    // outlives its response, so its children were exported before it and its logs
                    // arrived synchronously. A later event would rebuild the id as an empty,
                    // rootless bundle.
                    discardWhatItStored(otelSpan);
                    continue;
                }
                org.peekaboot.backend.tracing.event.SpanData spanData =
                        OtelSpanConverter.convert(otelSpan, tags, creationOrder.incrementAndGet());
                eventPublisher.publishEvent(new SpanDataEvent(spanData));
            } catch (RuntimeException e) {
                // A span that fails, usually in a listener, never costs the rest of its batch.
                warnOnce(otelSpan.getSpanContext(), e);
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    /**
     * A span without a parent began its trace, which goes whole. One continuing a remote caller
     * ends a probe that sent a {@code traceparent} header, or a call this application made to its
     * own excluded path, so the discard names the span and the store's listener tells the two
     * apart. Under a local parent the trace belongs to the request around it, and stays.
     */
    private void discardWhatItStored(SpanData skipped) {
        SpanContext span = skipped.getSpanContext();
        SpanContext parent = skipped.getParentSpanContext();
        if (!parent.isValid()) {
            eventPublisher.publishEvent(new TraceDiscardedEvent(span.getTraceId(), null));
        } else if (parent.isRemote()) {
            eventPublisher.publishEvent(new TraceDiscardedEvent(span.getTraceId(), span.getSpanId()));
        }
    }

    private void warnOnce(SpanContext span, RuntimeException e) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Peekaboot traces: could not store span {} of trace {} ({}); later failures are not logged",
                    span.getSpanId(),
                    span.getTraceId(),
                    e.toString());
        }
    }

    /**
     * A request on one of {@link PeekabootPaths}'s excluded prefixes, recognised by the span's
     * HTTP path. The path tag carries the servlet context path, so it goes through the
     * context-stripping check. Only SERVER spans are judged: the exclusions describe inbound
     * requests, and an outbound call to a remote {@code /actuator/health} is part of its trace.
     * Judged per span: the children of such a request carry no path and are stored, which is
     * what {@link #discardWhatItStored} undoes once the request's own span arrives.
     */
    private boolean shouldSkipSpan(SpanData span, Map<String, String> tags) {
        if (span.getKind() != SpanKind.SERVER) {
            return false;
        }
        String path = HttpSpanTags.path(tags);
        return path != null && paths.isExcludedRequestPath(path);
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
