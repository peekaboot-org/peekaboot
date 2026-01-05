package net.osslabz.peekaboot.tracing.bridge.brave;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BraveSpanHandler extends SpanHandler {

    private final InMemorySpanStore store;

    public BraveSpanHandler(InMemorySpanStore store) {
        this.store = store;
    }

    @Override
    public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        SpanData spanData = convertToSpanData(context, span);
        store.report(spanData);
        return true;
    }

    private SpanData convertToSpanData(TraceContext context, MutableSpan span) {
        Instant startTime = microsToInstant(span.startTimestamp());
        Instant endTime = microsToInstant(span.finishTimestamp());
        Duration duration = (startTime != null && endTime != null)
                ? Duration.between(startTime, endTime)
                : null;

        Map<String, String> tags = extractTags(span);
        List<SpanData.Event> events = extractEvents(span);

        Throwable error = span.error();

        return new SpanData(
                context.traceIdString(),
                context.spanIdString(),
                context.parentIdString(),
                span.name(),
                mapKind(span.kind()),
                startTime,
                endTime,
                duration,
                tags,
                events,
                error != null ? error.getMessage() : null,
                error != null ? error.getClass().getName() : null,
                span.remoteServiceName(),
                span.remoteIp(),
                span.remotePort() > 0 ? span.remotePort() : null,
                List.of(),
                store.nextCreationOrder()
        );
    }

    private Instant microsToInstant(long micros) {
        if (micros == 0) {
            return null;
        }
        return Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
    }

    private Map<String, String> extractTags(MutableSpan span) {
        Map<String, String> tags = new HashMap<>();
        span.forEachTag((target, key, value) -> {

            if (key != null && value != null) {
                target.put(key, value);
            }
        }, tags);
        return tags;
    }

    private List<SpanData.Event> extractEvents(MutableSpan span) {
        List<SpanData.Event> events = new ArrayList<>();
        span.forEachAnnotation(new MutableSpan.AnnotationConsumer<List<SpanData.Event>>() {
            @Override
            public void accept(List<SpanData.Event> target, long timestamp, String value) {
                if (value != null) {
                    target.add(new SpanData.Event(value, microsToInstant(timestamp)));
                }
            }
        }, events);
        return events;
    }

    private Span.Kind mapKind(brave.Span.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case CLIENT -> Span.Kind.CLIENT;
            case SERVER -> Span.Kind.SERVER;
            case PRODUCER -> Span.Kind.PRODUCER;
            case CONSUMER -> Span.Kind.CONSUMER;
        };
    }
}