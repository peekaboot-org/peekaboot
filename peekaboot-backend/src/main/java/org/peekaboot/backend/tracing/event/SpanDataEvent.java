package org.peekaboot.backend.tracing.event;

import org.peekaboot.backend.tracing.store.SpanData;

/** Carries one exported span from {@code OtelSpanExporter} to the store via Spring's event bus. */
public record SpanDataEvent(SpanData spanData) {

    public String traceId() {
        return spanData != null ? spanData.traceId() : null;
    }
}
