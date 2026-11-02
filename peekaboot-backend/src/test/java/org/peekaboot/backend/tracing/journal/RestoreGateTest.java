package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInput;

class RestoreGateTest {

    private static final Instant STARTED = Instant.parse("2026-10-31T08:00:00Z");

    private static TraceInput logged(String message) {
        return new TraceInput.LogCaptured(log("t1").saying(message).build());
    }

    private static Iterable<String> messages(InMemoryTraceStore store) {
        return store.getTrace("t1").orElseThrow().capturedLogs().logs().stream()
                .map(LogCapturedEvent::message)
                .toList();
    }

    @Test
    void holdsInputsUntilItOpensThenAppliesThemInOrder() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        RestoreGate gate = new RestoreGate(10);

        gate.pass(logged("a"), store);
        gate.pass(logged("b"), store);
        assertThat(store.getTrace("t1")).isEmpty();

        gate.open(store);
        gate.pass(logged("c"), store);

        assertThat(messages(store)).containsExactly("a", "b", "c");
    }

    /** An input that arrives while the held ones are being applied waits its turn behind them. */
    @Test
    void anInputArrivingWhileTheHeldOnesAreAppliedComesAfterThem() {
        RestoreGate gate = new RestoreGate(10);
        InMemoryTraceStore store = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void addLog(LogCapturedEvent log) {
                super.addLog(log);
                if ("a".equals(log.message())) {
                    gate.pass(logged("late"), this);
                }
            }
        };
        gate.pass(logged("a"), store);
        gate.pass(logged("b"), store);

        gate.open(store);

        assertThat(messages(store)).containsExactly("a", "b", "late");
    }

    @Test
    void dropsWhatItCannotHold() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        RestoreGate gate = new RestoreGate(2);

        assertThat(gate.pass(logged("a"), store)).isTrue();
        assertThat(gate.pass(logged("b"), store)).isTrue();
        assertThat(gate.pass(logged("c"), store)).isFalse();
        gate.open(store);

        assertThat(messages(store)).containsExactly("a", "b");
        assertThat(gate.dropped()).isEqualTo(1);
    }

    /** The run's start is held even past the capacity, or no trace of the run would learn when it started. */
    @Test
    void holdsTheRunStartWhenFull() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        RestoreGate gate = new RestoreGate(1);
        gate.pass(logged("a"), store);

        assertThat(gate.pass(new TraceInput.RunStarted(STARTED), store)).isTrue();
        assertThat(gate.pass(logged("b"), store)).isFalse();
        gate.open(store);

        assertThat(store.getTrace("t1").orElseThrow().snapshot().run()).isEqualTo(STARTED);
        assertThat(messages(store)).containsExactly("a");
        assertThat(gate.dropped()).isEqualTo(1);
    }

    /** An input that throws costs itself alone, as it would live: the gate applies the rest and opens. */
    @Test
    void opensEvenWhenAHeldInputThrows() {
        InMemoryTraceStore store = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void addLog(LogCapturedEvent log) {
                if ("bad".equals(log.message())) {
                    throw new IllegalStateException("refused");
                }
                super.addLog(log);
            }
        };
        RestoreGate gate = new RestoreGate(10);
        gate.pass(logged("a"), store);
        gate.pass(logged("bad"), store);
        gate.pass(logged("b"), store);

        gate.open(store);
        gate.pass(logged("c"), store);

        assertThat(messages(store)).containsExactly("a", "b", "c");
        assertThat(gate.failed()).isEqualTo(1);
        assertThat(gate.firstFailure()).hasMessage("refused");
    }
}
