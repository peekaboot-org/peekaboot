package org.peekaboot.backend.tracing.event;

import org.jspecify.annotations.Nullable;

/**
 * Tells the store to drop a trace whose root span turned out to be one of Peekaboot's
 * own requests - its children, logs and request were stored before the root was exported.
 *
 * @param continuingSpanId the excluded server span where it continued a remote caller's trace, else null
 */
public record TraceDiscardedEvent(String traceId, @Nullable String continuingSpanId) {}
