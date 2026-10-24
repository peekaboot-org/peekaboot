package org.peekaboot.backend.tracing.event;

/** Carries one exported span from {@code OtelSpanExporter} to the store via Spring's event bus. */
public record SpanDataEvent(SpanData spanData) {}
