package net.osslabz.peekaboot.backend.tracing.event;

public sealed interface TraceDataEvent permits
        SpanCompletedEvent,
        LogCapturedEvent,
        RequestCompletedEvent {
    String traceId();
}
