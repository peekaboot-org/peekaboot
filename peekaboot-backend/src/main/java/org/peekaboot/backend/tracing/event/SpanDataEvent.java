package org.peekaboot.backend.tracing.event;

/** Carries one exported span from {@code OtelSpanExporter} to the store's listener. */
public record SpanDataEvent(SpanData spanData) {}
