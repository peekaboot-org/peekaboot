package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanDataEvent;

class TraceStoreEventListenerTest {

    private InMemoryTraceStore store;
    private TraceStoreEventListener listener;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        listener = new TraceStoreEventListener(store);
    }

    @Test
    void onSpanData_forwardsSpanToStore() {
        SpanData span = new SpanData(
                "trace1",
                "span1",
                null,
                "op",
                null,
                Instant.now(),
                Instant.now(),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                1);

        listener.onSpanData(new SpanDataEvent(span));

        assertThat(store.getTrace("trace1")).isPresent();
    }

    @Test
    void onSpanData_ignoresNullEventAndNullSpan() {
        listener.onSpanData(null);
        listener.onSpanData(new SpanDataEvent(null));
        // no exception, nothing stored — nothing to assert beyond absence
        assertThat(store.getTrace("trace1")).isEmpty();
    }

    @Test
    void onLogCaptured_forwardsLogToStore() {
        listener.onLogCaptured(
                new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", "msg", "main"));

        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.getTrace("trace1").get().logs()).hasSize(1);
    }

    @Test
    void onRequestCompleted_forwardsRequestToStore() {
        listener.onRequestCompleted(new RequestCompletedEvent(
                "trace1", "GET", "/x", null, Map.of(), null, false, null, null, Map.of(), Map.of(), List.of(), 200,
                Map.of(), 10));

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
