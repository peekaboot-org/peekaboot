package org.peekaboot.backend.tracing.bridge.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Copies every span the host's OpenTelemetry SDK exports into Peekaboot's own store, as a
 * {@link SpanDataEvent} per span, leaving out requests on the excluded prefixes. Runs beside
 * whatever other exporter the application has configured, never instead of it.
 */
public class OtelSpanExporter implements SpanExporter {

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
            Map<String, String> tags = OtelSpanConverter.tags(otelSpan);
            if (shouldSkipSpan(otelSpan, tags)) {
                // A skipped root ends an excluded request's trace. The discard assumes
                // nothing more arrives under that id: the excluded prefixes serve no endpoint
                // that outlives its response, so the children exported alongside the root and
                // the logs arrived synchronously. A later event would rebuild the id as an
                // empty, rootless bundle.
                if (!otelSpan.getParentSpanContext().isValid()) {
                    eventPublisher.publishEvent(
                            new TraceDiscardedEvent(otelSpan.getSpanContext().getTraceId()));
                }
                continue;
            }
            org.peekaboot.backend.tracing.event.SpanData spanData =
                    OtelSpanConverter.convert(otelSpan, tags, creationOrder.incrementAndGet());
            eventPublisher.publishEvent(new SpanDataEvent(spanData));
        }
        return CompletableResultCode.ofSuccess();
    }

    /**
     * A request on one of {@link PeekabootPaths}'s excluded prefixes, recognised by the span's
     * HTTP path. The path tag carries the servlet context path, so it goes through the
     * context-stripping check. Only SERVER spans are judged: the exclusions describe inbound
     * requests, and an outbound call to a remote {@code /actuator/health} is part of its trace.
     * Judged per span: the children of such a request carry no path and are stored, which is
     * what the discard in {@link #export} undoes once their root arrives.
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
