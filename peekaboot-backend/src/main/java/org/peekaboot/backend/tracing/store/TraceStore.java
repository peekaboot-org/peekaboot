package org.peekaboot.backend.tracing.store;

import java.util.List;
import java.util.Optional;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/** Receives raw span data, logs and request info and answers per-trace lookups. */
public interface TraceStore {

    void addSpan(SpanData span);

    void addLog(LogCapturedEvent log);

    void setRequest(RequestCompletedEvent request);

    /**
     * Drops everything stored for {@code traceId}, from every bucket - a trace whose root
     * span turned out to be one of Peekaboot's own requests.
     */
    void discard(String traceId);

    Optional<TraceDataBundle> getTrace(String traceId);

    List<TraceDataBundle> getTraces(TraceBucket bucket, int limit);

    int getTraceCount(TraceBucket bucket);

    /** Test hook: drops every trace from every bucket. Nothing in production calls it. */
    void clear();
}
