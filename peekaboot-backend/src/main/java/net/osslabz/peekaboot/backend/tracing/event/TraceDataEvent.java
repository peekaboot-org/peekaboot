package net.osslabz.peekaboot.backend.tracing.event;

/**
 * Sealed interface for trace data events that are published via Spring's event mechanism.
 * These events carry trace-related data (logs, request info) to the TraceStore.
 *
 * Note: Span data is published separately via SpanDataEvent which wraps the raw SpanData record.
 */
public sealed interface TraceDataEvent permits
        LogCapturedEvent,
        RequestCompletedEvent {
    String traceId();
}
