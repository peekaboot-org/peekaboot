package net.osslabz.peekaboot.tracing.event;

public sealed interface TraceDataEvent permits
        SpanCompletedEvent,
        LogCapturedEvent,
        RequestCompletedEvent {
    String traceId();
}
