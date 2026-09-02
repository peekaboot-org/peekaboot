package org.peekaboot.backend.tracing.event;

/**
 * Tells the store to drop a trace whose root span turned out to be one of Peekaboot's
 * own requests - its children, logs and request were stored before the root was exported.
 */
public record TraceDiscardedEvent(String traceId) {}
