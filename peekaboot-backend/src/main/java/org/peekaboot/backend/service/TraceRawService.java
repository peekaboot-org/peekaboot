package org.peekaboot.backend.service;

import org.peekaboot.backend.domain.trace.CollectionFramework;
import org.peekaboot.backend.domain.trace.TraceRawData;
import org.peekaboot.backend.domain.trace.TraceRawResponse;
import org.peekaboot.backend.domain.trace.TraceRawSummary;
import org.peekaboot.backend.mapper.trace.TraceRawMapper;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TraceRawService {

    @Nullable
    private final TraceStore traceStore;
    private final TraceRawMapper traceRawMapper;
    private final CollectionFramework collectionFramework;

    public TraceRawService(
            @Nullable TraceStore traceStore,
            TraceRawMapper traceRawMapper) {
        this.traceStore = traceStore;
        this.traceRawMapper = traceRawMapper;
        this.collectionFramework = detectCollectionFramework();
    }

    private CollectionFramework detectCollectionFramework() {
        try {
            Class.forName("io.opentelemetry.sdk.trace.export.SpanExporter");
            return CollectionFramework.OTEL;
        } catch (ClassNotFoundException e) {
            return CollectionFramework.BRAVE;
        }
    }

    /**
     * Tracing is wired only when peekaboot.tracing.enabled is true; without it
     * the nullable collaborators stay absent and all trace endpoints are empty.
     */
    public boolean isTracingAvailable() {
        return traceStore != null;
    }

    public TraceRawResponse getTraces(int limit, TraceBucket bucket) {
        if (traceStore == null) {
            return TraceRawResponse.empty(collectionFramework);
        }

        List<TraceRawData> rawTraces = traceStore.getTraces(bucket, limit).stream()
                .map(bundle -> traceRawMapper.map(bundle, collectionFramework))
                .toList();

        TraceRawSummary summary = traceRawMapper.calculateSummary(rawTraces);

        return new TraceRawResponse(collectionFramework, summary, rawTraces);
    }

    public Optional<TraceRawData> getTrace(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId)
                .map(bundle -> traceRawMapper.map(bundle, collectionFramework));
    }
}
