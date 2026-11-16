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
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
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
        listener = new TraceStoreEventListener(TraceInputs.into(store), store, ownContext);
    }

    /** The ready event goes the same way, so the trace journal's restore gate holds it back like any input. */
    @Test
    void handsTheReadyEventOnAsAnInput() {
        List<TraceInput> handedOn = new ArrayList<>();
        TraceStoreEventListener recording = new TraceStoreEventListener(handedOn::add, store, ownContext);
        ApplicationReadyEvent ready =
                new ApplicationReadyEvent(new SpringApplication(), new String[0], ownContext, Duration.ZERO);

        recording.onApplicationReady(ready);

        assertThat(handedOn).containsExactly(new TraceInput.RunStarted(Instant.ofEpochMilli(ready.getTimestamp())));
    }

    @Test
    void onSpanDataForwardsSpanToStore() {
        listener.onSpanData(new SpanDataEvent(span("span1").order(1).build()));

        assertThat(store.getTrace("trace1")).isPresent();
    }

    @Test
    void onLogCapturedForwardsLogToStore() {
        listener.onLogCaptured(log("trace1").build());

        assertThat(store.getTrace("trace1").orElseThrow().capturedLogs().logs()).hasSize(1);
    }

    @Test
    void onRequestCompletedForwardsRequestToStore() {
        RequestCompletedEvent event = RequestCompletedEvents.minimal("trace1");

        listener.onRequestCompleted(event);

        assertThat(store.getTrace("trace1").orElseThrow().request()).isSameAs(event);
    }

    @Test
    void onTraceDiscardedRemovesTheTraceFromTheStore() {
        listener.onSpanData(new SpanDataEvent(span("span1").build()));

        listener.onTraceDiscarded(new TraceDiscardedEvent("trace1", null));

        assertThat(store.getTrace("trace1")).isEmpty();
    }

    /** A parentless excluded root began the trace itself, so nothing under its id is another request's. */
    @Test
    void aTraceAnExcludedRootBeganIsDiscardedWhateverItHolds() {
        listener.onSpanData(
                new SpanDataEvent(span("foreign").parent("elsewhere").build()));

        listener.onTraceDiscarded(new TraceDiscardedEvent("trace1", null));

        assertThat(store.getTrace("trace1")).isEmpty();
    }

    /** {@code S} is the probe's skipped server span: what the health check stored hangs from it and goes with it. */
    @Test
    void aProbeContinuingARemoteCallerIsDiscarded() {
        listener.onSpanData(new SpanDataEvent(span("child").parent("S").build()));

        listener.onTraceDiscarded(new TraceDiscardedEvent("trace1", "S"));

        assertThat(store.getTrace("trace1")).isEmpty();
    }

    /** Its inner server span continues a remote caller as a probe's does, and the caller is the request itself. */
    @Test
    void aRequestThatCalledItsOwnExcludedPathKeepsItsTrace() {
        listener.onSpanData(new SpanDataEvent(span("child").parent("S").build()));
        listener.onLogCaptured(log("trace1").inSpan("outer-handler").at("ERROR").build());

        listener.onTraceDiscarded(new TraceDiscardedEvent("trace1", "S"));

        assertThat(store.getTrace("trace1")).isPresent();
        assertThat(store.bucketsOf("trace1")).contains(TraceBucket.ERRORS);
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
