package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;

class TraceStoreEventListenerTest {

    private InMemoryTraceStore store;
    private TraceStoreEventListener listener;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        listener = new TraceStoreEventListener(store);
    }

    @Test
    void onSpanData_forwardsSpanToStore() {
        listener.onSpanData(new SpanDataEvent(span("span1").order(1).build()));

        assertThat(store.getTrace("trace1")).isPresent();
    }

    @Test
    void onLogCaptured_forwardsLogToStore() {
        listener.onLogCaptured(log("trace1").build());

        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.getTrace("trace1").get().logs()).hasSize(1);
    }

    @Test
    void onRequestCompleted_forwardsRequestToStore() {
        RequestCompletedEvent event = RequestCompletedEvents.minimal("trace1");

        listener.onRequestCompleted(event);

        assertThat(store.getTrace("trace1").orElseThrow().request()).isSameAs(event);
    }

    @Test
    void onTraceDiscarded_removesTheTraceFromTheStore() {
        listener.onSpanData(new SpanDataEvent(span("span1").build()));

        listener.onTraceDiscarded(new TraceDiscardedEvent("trace1"));

        assertThat(store.getTrace("trace1")).isEmpty();
    }
}
