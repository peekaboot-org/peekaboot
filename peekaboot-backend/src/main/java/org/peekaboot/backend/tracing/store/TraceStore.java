package org.peekaboot.backend.tracing.store;

import java.util.List;
import java.util.Optional;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/**
 * Central storage abstraction for all trace data. Implementations receive raw
 * span data, logs, and request info and answer per-trace lookups.
 */
public interface TraceStore {

    long nextCreationOrder();

    void addSpan(SpanData span);

    void addLog(LogCapturedEvent log);

    void setRequest(RequestCompletedEvent request);

    Optional<TraceDataBundle> getTrace(String traceId);

    List<TraceDataBundle> getTraces(TraceBucket bucket, int limit);

    int getTraceCount(TraceBucket bucket);

    /**
     * Test hook: drops every trace from every bucket. Nothing in production calls it; the
     * integration suite's classes that share one application context reset the store with
     * it before each test so they can assert exact counts.
     */
    void clear();
}
