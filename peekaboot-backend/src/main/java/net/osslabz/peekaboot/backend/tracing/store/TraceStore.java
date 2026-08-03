package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;

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

    void clear();

    void cleanUp();
}
