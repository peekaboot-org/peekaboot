package org.peekaboot.backend.tracing.store;

import java.time.Instant;
import org.peekaboot.backend.lifecycle.ContextEvents;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;

/** The store's single write entry point: one {@code @EventListener} per event type the capture side publishes. */
public class TraceStoreEventListener {

    private final TraceStore store;
    private final ApplicationContext ownContext;

    public TraceStoreEventListener(TraceStore store, ApplicationContext ownContext) {
        this.store = store;
        this.ownContext = ownContext;
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

    /** The run starts at the ready event's own timestamp, the instant the lifecycle log stamps the run with. */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (ContextEvents.fromOwnContext(event, ownContext)) {
            store.markRunStarted(Instant.ofEpochMilli(event.getTimestamp()));
        }
    }
}
