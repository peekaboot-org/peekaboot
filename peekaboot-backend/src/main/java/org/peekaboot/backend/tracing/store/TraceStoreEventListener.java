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
 * The store's single write entry point: one method per event the capture side publishes, which
 * {@link CaptureEventPublisher} calls, and an {@code @EventListener} for the run's ready event, each
 * handed on as a {@link TraceInput} to the trace journal where one runs, else straight into the
 * store. A discard is handed on only for a trace that holds the excluded request and nothing else.
 */
public class TraceStoreEventListener {

    private final TraceInputs inputs;
    private final TraceStore store;
    private final ApplicationContext ownContext;

    /** @param store the store {@code inputs} end up in, read to judge a discard */
    public TraceStoreEventListener(TraceInputs inputs, TraceStore store, ApplicationContext ownContext) {
        this.inputs = inputs;
        this.store = store;
        this.ownContext = ownContext;
    }

    public void onSpanData(SpanDataEvent event) {
        inputs.accept(new TraceInput.SpanArrived(event.spanData()));
    }

    public void onLogCaptured(LogCapturedEvent event) {
        inputs.accept(new TraceInput.LogCaptured(event));
    }

    public void onRequestCompleted(RequestCompletedEvent event) {
        inputs.accept(new TraceInput.RequestCompleted(event));
    }

    public void onTraceDiscarded(TraceDiscardedEvent event) {
        if (holdsOnlyTheExcludedRequest(event)) {
            inputs.accept(new TraceInput.TraceDiscarded(event.traceId()));
        }
    }

    /**
     * A trace the excluded span began holds nothing else. One it continued from a remote caller may
     * be a request of this application that called its own excluded path, and keeps what it stored.
     */
    private boolean holdsOnlyTheExcludedRequest(TraceDiscardedEvent event) {
        String continuingSpanId = event.continuingSpanId();
        return continuingSpanId == null
                || store.getTrace(event.traceId())
                        .filter(bundle -> bundle.liesUnder(continuingSpanId))
                        .isPresent();
    }

    /** The run starts at the ready event's own timestamp, the instant the lifecycle log stamps the run with. */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (ContextEvents.fromOwnContext(event, ownContext)) {
            inputs.accept(new TraceInput.RunStarted(Instant.ofEpochMilli(event.getTimestamp())));
        }
    }
}
