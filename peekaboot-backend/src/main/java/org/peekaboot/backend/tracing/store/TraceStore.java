package org.peekaboot.backend.tracing.store;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;

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

    /** The bucket's traces, newest first by when each entered the bucket. */
    List<ListedTrace> getTraces(TraceBucket bucket, int limit);

    int getTraceCount(TraceBucket bucket);

    /** The Errors and Slow buckets listing {@code traceId}. All lists every trace, so it is never named. */
    Set<TraceBucket> bucketsOf(String traceId);

    /** Every trace any bucket lists, each once: the All bucket's, newest first, then those only Errors or Slow still keep. */
    List<TraceDataBundle> storedTraces();

    /**
     * The log lines written outside any trace that the untraced ring still holds, oldest first,
     * copied under the ring's own lock, which no bucket read takes.
     */
    List<LogCapturedEvent> untracedLogs();
}
