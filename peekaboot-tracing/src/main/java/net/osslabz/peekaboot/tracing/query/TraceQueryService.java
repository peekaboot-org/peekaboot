package net.osslabz.peekaboot.tracing.query;

import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;

import java.util.List;
import java.util.Optional;

public class TraceQueryService {

    private final InMemorySpanStore store;

    public TraceQueryService(InMemorySpanStore store) {
        this.store = store;
    }

    public Optional<TraceData> getTrace(String traceId) {
        return store.getTrace(traceId);
    }

    public List<SpanData> getSpans(String traceId) {
        return store.getSpansForTrace(traceId);
    }

    public List<TraceData> getRecentTraces(int limit) {
        return store.getRecentTraces(limit);
    }

    public List<TraceData> getAllTraces() {
        return store.getAllTraces();
    }

    public List<TraceData> getErrorTraces(int limit) {
        return store.getAllTraces().stream()
                .filter(TraceData::hasErrors)
                .limit(limit)
                .toList();
    }

    public int getTraceCount() {
        return store.getTraceCount();
    }
}
