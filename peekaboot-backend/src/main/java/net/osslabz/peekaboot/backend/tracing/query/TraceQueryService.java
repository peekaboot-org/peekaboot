package net.osslabz.peekaboot.backend.tracing.query;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Service for querying trace data from storage.
 */
public class TraceQueryService {

    private final TraceDataStorage storage;

    public TraceQueryService(TraceDataStorage storage) {
        this.storage = storage;
    }

    public Optional<TraceData> getTrace(String traceId) {
        return storage.getTraceData(traceId);
    }

    public List<SpanData> getSpans(String traceId) {
        return storage.getSpansForTrace(traceId);
    }

    public List<TraceData> getAllTraces() {
        return storage.getAllTraces();
    }

    public List<TraceData> getTraces(int limit, TraceCaptureMode mode) {
        Stream<TraceData> stream = storage.getAllTraces().stream();
        if (mode == TraceCaptureMode.ERRORS_ONLY) {
            stream = stream.filter(TraceData::hasErrors);
        }
        return stream.limit(limit).toList();
    }

    public int getTraceCount() {
        return storage.getTraceCount();
    }
}
