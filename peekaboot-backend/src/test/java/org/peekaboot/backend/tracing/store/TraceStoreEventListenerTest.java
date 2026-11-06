package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.StaticApplicationContext;

class TraceStoreEventListenerTest {

    private InMemoryTraceStore store;
    private TraceStoreEventListener listener;
    private StaticApplicationContext ownContext;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        ownContext = new StaticApplicationContext();
        listener = new TraceStoreEventListener(TraceInputs.into(store), ownContext);
    }

    /** The ready event goes the same way, so the trace journal's restore gate holds it back like any input. */
    @Test
    void handsEveryEventOnAsAnInput() {
        List<TraceInput> handedOn = new ArrayList<>();
        TraceStoreEventListener recording = new TraceStoreEventListener(handedOn::add, ownContext);
        SpanData spanData = span("span1").build();
        LogCapturedEvent logged = log("trace1").build();
        RequestCompletedEvent request = RequestCompletedEvents.minimal("trace1");
        ApplicationReadyEvent ready =
                new ApplicationReadyEvent(new SpringApplication(), new String[0], ownContext, Duration.ZERO);

        recording.onSpanData(new SpanDataEvent(spanData));
        recording.onLogCaptured(logged);
        recording.onRequestCompleted(request);
        recording.onTraceDiscarded(new TraceDiscardedEvent("trace1"));
        recording.onApplicationReady(ready);

        assertThat(handedOn)
                .containsExactly(
                        new TraceInput.SpanArrived(spanData),
                        new TraceInput.LogCaptured(logged),
                        new TraceInput.RequestCompleted(request),
                        new TraceInput.TraceDiscarded("trace1"),
                        new TraceInput.RunStarted(Instant.ofEpochMilli(ready.getTimestamp())));
    }

    @Test
    void onSpanData_forwardsSpanToStore() {
        listener.onSpanData(new SpanDataEvent(span("span1").order(1).build()));

        assertThat(store.getTrace("trace1")).isPresent();
    }

    @Test
    void onLogCaptured_forwardsLogToStore() {
        listener.onLogCaptured(log("trace1").build());

        assertThat(store.getTrace("trace1").orElseThrow().capturedLogs().logs()).hasSize(1);
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

    /** The run starts at the ready event's own timestamp, the instant the lifecycle log stamps it with. */
    @Test
    void theReadyEventOfItsOwnContextStartsTheRun() {
        listener.onSpanData(new SpanDataEvent(span("span1").build()));
        ApplicationReadyEvent ready =
                new ApplicationReadyEvent(new SpringApplication(), new String[0], ownContext, Duration.ZERO);

        listener.onApplicationReady(ready);

        assertThat(store.getTrace("trace1").orElseThrow().snapshot().run())
                .isEqualTo(Instant.ofEpochMilli(ready.getTimestamp()));
    }

    /** A child context's ready event reaches its parent's listeners too, and names another start. */
    @Test
    void anotherContextsReadyEventStartsNothing() {
        listener.onSpanData(new SpanDataEvent(span("span1").build()));

        listener.onApplicationReady(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], new StaticApplicationContext(), Duration.ZERO));

        assertThat(store.getTrace("trace1").orElseThrow().snapshot().run()).isNull();
    }
}
