package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.springframework.context.event.EventListener;

/**
 * Receives trace data via Spring's event mechanism and forwards it to the
 * {@link TraceStore}.
 */
public class TraceStoreEventListener {

    private final TraceStore store;

    public TraceStoreEventListener(TraceStore store) {
        this.store = store;
    }

    @EventListener
    public void onSpanData(SpanDataEvent event) {
        if (event == null || event.spanData() == null) {
            return;
        }
        store.addSpan(event.spanData());
    }

    @EventListener
    public void onLogCaptured(LogCapturedEvent event) {
        if (event == null) {
            return;
        }
        store.addLog(event);
    }

    @EventListener
    public void onRequestCompleted(RequestCompletedEvent event) {
        if (event == null) {
            return;
        }
        store.setRequest(event);
    }
}
