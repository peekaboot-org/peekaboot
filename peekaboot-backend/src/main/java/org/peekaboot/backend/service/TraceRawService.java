package org.peekaboot.backend.service;

import org.peekaboot.backend.domain.trace.CollectionFramework;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceRawData;
import org.peekaboot.backend.domain.trace.TraceRawResponse;
import org.peekaboot.backend.domain.trace.TraceRawSummary;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TraceRawService {

    @Nullable
    private final TraceStore traceStore;
    private final QueryExtractor queryExtractor;
    private final CollectionFramework collectionFramework;
    private final TagMasker tagMasker = new TagMasker(new MaskingEngine());

    public TraceRawService(
            @Nullable TraceStore traceStore,
            QueryExtractor queryExtractor) {
        this.traceStore = traceStore;
        this.queryExtractor = queryExtractor;
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
                .map(this::mapToRawData)
                .toList();

        TraceRawSummary summary = calculateSummary(rawTraces);

        return new TraceRawResponse(collectionFramework, summary, rawTraces);
    }

    public Optional<TraceRawData> getTrace(String traceId) {
        if (traceStore == null) {
            return Optional.empty();
        }

        return traceStore.getTrace(traceId)
                .map(this::mapToRawData);
    }

    private TraceRawData mapToRawData(TraceDataBundle bundle) {
        TraceData traceData = TraceData.fromSpans(bundle.traceId(), bundle.spans());
        List<QueryInfo> queries = queryExtractor.extract(traceData);

        // This endpoint embeds SpanData directly - unlike the insights endpoints, it
        // never passes through TraceTreeMapper, so tag masking has to happen here.
        List<SpanData> maskedSpans = traceData.spans().stream()
                .map(this::maskSpanTags)
                .toList();

        List<TraceLog> logs = bundle.logs().stream()
                .map(e -> new TraceLog(
                        e.spanId(),
                        e.timestamp(),
                        e.level(),
                        e.loggerName(),
                        e.message(),
                        e.threadName()
                ))
                .toList();

        HttpExchange httpExchange = null;
        RequestCompletedEvent reqEvent = bundle.request();
        if (reqEvent != null) {
            httpExchange = HttpExchange.from(reqEvent);
        }

        int errorCount = (int) traceData.spans().stream().filter(SpanData::hasError).count();
        long spansDurationMs = traceData.spans().stream()
                .filter(s -> s.duration() != null)
                .mapToLong(s -> s.duration().toMillis())
                .sum();
        long queryDurationMs = queries.stream()
                .mapToLong(QueryInfo::durationMs)
                .sum();

        TraceRawData.PerTraceSummary perTraceSummary = new TraceRawData.PerTraceSummary(
                new TraceRawSummary.CountDuration(traceData.spanCount(), spansDurationMs),
                new TraceRawSummary.CountDuration(queries.size(), queryDurationMs),
                new TraceRawSummary.Count(logs.size()),
                new TraceRawSummary.Count(errorCount)
        );

        return new TraceRawData(
                collectionFramework,
                traceData.traceId(),
                traceData.startTime(),
                traceData.endTime(),
                traceData.duration() != null ? traceData.duration().toMillis() : 0,
                perTraceSummary,
                maskedSpans,
                logs,
                queries,
                httpExchange
        );
    }

    private SpanData maskSpanTags(SpanData span) {
        return new SpanData(
                span.traceId(), span.spanId(), span.parentId(), span.name(), span.kind(),
                span.startTime(), span.endTime(), span.duration(),
                tagMasker.mask(span.tags()),
                span.events(), span.errorMessage(), span.errorClass(), span.remoteServiceName(),
                span.remoteIp(), span.remotePort(), span.links(), span.creationOrder()
        );
    }

    private TraceRawSummary calculateSummary(List<TraceRawData> traces) {
        if (traces.isEmpty()) {
            return TraceRawSummary.empty();
        }

        int traceCount = traces.size();
        int totalSpans = 0;
        long totalSpanDurationMs = 0;
        int totalQueries = 0;
        long totalQueryDurationMs = 0;
        int totalLogs = 0;
        int totalErrors = 0;

        for (TraceRawData trace : traces) {
            totalSpans += trace.summary().spans().count();
            totalSpanDurationMs += trace.summary().spans().totalDurationMs();
            totalQueries += trace.summary().queries().count();
            totalQueryDurationMs += trace.summary().queries().totalDurationMs();
            totalLogs += trace.summary().logs().count();
            totalErrors += trace.summary().errors().count();
        }

        return new TraceRawSummary(
                traceCount,
                new TraceRawSummary.CountDuration(totalSpans, totalSpanDurationMs),
                new TraceRawSummary.CountDuration(totalQueries, totalQueryDurationMs),
                new TraceRawSummary.Count(totalLogs),
                new TraceRawSummary.Count(totalErrors)
        );
    }
}
