package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

class TraceInputTest {

    private InMemoryTraceStore store;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
    }

    @Test
    void eachInputReachesTheStoreThroughItsOwnMethod() {
        TraceInputs inputs = TraceInputs.into(store);

        inputs.accept(new TraceInput.SpanArrived(span("s1").in("t1").build()));
        inputs.accept(new TraceInput.RequestCompleted(
                RequestCompletedEvents.request("t1").path("/persons").build()));
        inputs.accept(new TraceInput.LogCaptured(untraced().saying("startup").build()));
        inputs.accept(new TraceInput.SpanArrived(span("s2").in("t2").build()));
        inputs.accept(new TraceInput.TraceDiscarded("t2"));
        inputs.accept(new TraceInput.RunStarted(Instant.parse("2026-10-31T08:00:00Z")));

        assertThat(store.getTrace("t1").orElseThrow().request().path()).isEqualTo("/persons");
        assertThat(store.untracedLogs()).extracting(LogCapturedEvent::message).containsExactly("startup");
        assertThat(store.getTrace("t2")).isEmpty();
        assertThat(store.getTrace("t1").orElseThrow().snapshot().run())
                .isEqualTo(Instant.parse("2026-10-31T08:00:00Z"));
    }
}
