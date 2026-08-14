package org.peekaboot.backend.tracing.event;

import org.peekaboot.backend.tracing.store.SpanData;

/**
 * Event published when a span completes. Contains the raw SpanData.
 * Published via Spring's ApplicationEventPublisher.
 */
public record SpanDataEvent(SpanData spanData) {

    public String traceId() {
        return spanData != null ? spanData.traceId() : null;
    }
}
