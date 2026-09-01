package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;

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
    void onSpanData_ignoresNullEventAndNullSpan() {
        listener.onSpanData(null);
        listener.onSpanData(new SpanDataEvent(null));
        // no exception, nothing stored - nothing to assert beyond absence
        assertThat(store.getTrace("trace1")).isEmpty();
    }

    @Test
    void onLogCaptured_forwardsLogToStore() {
        listener.onLogCaptured(
                new LogCapturedEvent("trace1", "span1", Instant.EPOCH, "INFO", "TestLogger", "msg", "main"));

        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.getTrace("trace1").get().logs()).hasSize(1);
    }

    @Test
    void onRequestCompleted_forwardsRequestToStore() {
        listener.onRequestCompleted(RequestCompletedEvents.minimal("trace1"));

        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.getTrace("trace1").get().request()).isNotNull();
    }

    @Test
    void nullEventsAreIgnored() {
        listener.onLogCaptured(null);
        listener.onRequestCompleted(null);
        assertThat(store.getTrace("trace1")).isEmpty();
    }
}
