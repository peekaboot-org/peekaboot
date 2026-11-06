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

/**
 * The store's single write entry point: one {@code @EventListener} per event type the capture side
 * publishes, and the run's ready event, each handed on as a {@link TraceInput} to the trace journal
 * where one runs, else straight into the store.
 */
public class TraceStoreEventListener {

    private final TraceInputs inputs;
    private final ApplicationContext ownContext;

    public TraceStoreEventListener(TraceInputs inputs, ApplicationContext ownContext) {
        this.inputs = inputs;
        this.ownContext = ownContext;
    }

    @EventListener
    public void onSpanData(SpanDataEvent event) {
        inputs.accept(new TraceInput.SpanArrived(event.spanData()));
    }

    @EventListener
    public void onLogCaptured(LogCapturedEvent event) {
        inputs.accept(new TraceInput.LogCaptured(event));
    }

    @EventListener
    public void onRequestCompleted(RequestCompletedEvent event) {
        inputs.accept(new TraceInput.RequestCompleted(event));
    }

    @EventListener
    public void onTraceDiscarded(TraceDiscardedEvent event) {
        inputs.accept(new TraceInput.TraceDiscarded(event.traceId()));
    }

    /** The run starts at the ready event's own timestamp, the instant the lifecycle log stamps the run with. */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (ContextEvents.fromOwnContext(event, ownContext)) {
            inputs.accept(new TraceInput.RunStarted(Instant.ofEpochMilli(event.getTimestamp())));
        }
    }
}
