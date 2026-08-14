package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.util.List;
import java.util.Optional;

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

    void clear();

    void cleanUp();
}
