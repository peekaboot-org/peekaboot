package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.context.event.EventListener;

/** The store's single write entry point: one {@code @EventListener} per event type the capture side publishes. */
public class TraceStoreEventListener {

    private final TraceStore store;

    public TraceStoreEventListener(TraceStore store) {
        this.store = store;
    }

    @EventListener
    public void onSpanData(SpanDataEvent event) {
        store.addSpan(event.spanData());
    }

    @EventListener
    public void onLogCaptured(LogCapturedEvent event) {
        store.addLog(event);
    }

    @EventListener
    public void onRequestCompleted(RequestCompletedEvent event) {
        store.setRequest(event);
    }

    @EventListener
    public void onTraceDiscarded(TraceDiscardedEvent event) {
        store.discard(event.traceId());
    }
}
