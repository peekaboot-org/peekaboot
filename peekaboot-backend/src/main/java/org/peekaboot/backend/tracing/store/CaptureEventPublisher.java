package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.context.ApplicationEventPublisher;

/** Hands what capture publishes straight to the store's listener, never to the application's event listeners. */
public final class CaptureEventPublisher implements ApplicationEventPublisher {

    private final TraceStoreEventListener listener;

    public CaptureEventPublisher(TraceStoreEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void publishEvent(Object event) {
        switch (event) {
            case SpanDataEvent span -> listener.onSpanData(span);
            case LogCapturedEvent log -> listener.onLogCaptured(log);
            case RequestCompletedEvent request -> listener.onRequestCompleted(request);
            case TraceDiscardedEvent discard -> listener.onTraceDiscarded(discard);
            default ->
                throw new IllegalArgumentException(
                        "Not a capture event: " + event.getClass().getName());
        }
    }
}
