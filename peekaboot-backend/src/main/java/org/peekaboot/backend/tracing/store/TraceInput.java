package org.peekaboot.backend.tracing.store;

import java.time.Instant;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * One input the store takes: what {@link TraceStoreEventListener} receives, and the run reporting
 * ready. The trace journal records these and replays them through the same store methods, so a
 * restored store is built the way a live one is. A line written outside any trace is a
 * {@link LogCaptured} whose event has no trace id.
 */
public sealed interface TraceInput {

    void applyTo(TraceStore store);

    record SpanArrived(SpanData span) implements TraceInput {
        @Override
        public void applyTo(TraceStore store) {
            store.addSpan(span);
        }
    }

    record LogCaptured(LogCapturedEvent log) implements TraceInput {
        @Override
        public void applyTo(TraceStore store) {
            store.addLog(log);
        }
    }

    record RequestCompleted(RequestCompletedEvent request) implements TraceInput {
        @Override
        public void applyTo(TraceStore store) {
            store.setRequest(request);
        }
    }

    record TraceDiscarded(String traceId) implements TraceInput {
        @Override
        public void applyTo(TraceStore store) {
            store.discard(traceId);
        }
    }

    record RunStarted(Instant startedAt) implements TraceInput {
        @Override
        public void applyTo(TraceStore store) {
            store.markRunStarted(startedAt);
        }
    }
}
