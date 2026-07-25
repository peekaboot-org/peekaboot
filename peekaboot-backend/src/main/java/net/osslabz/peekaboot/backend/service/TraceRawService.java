package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.domain.trace.CollectionFramework;
import net.osslabz.peekaboot.backend.domain.trace.HttpExchange;
import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.backend.domain.trace.TraceLog;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawData;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawSummary;
import net.osslabz.peekaboot.backend.mapper.trace.QueryExtractor;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataBundle;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TraceRawService {

    @Nullable
    private final TraceQueryService traceQueryService;
    @Nullable
    private final TraceDataStorage traceDataStorage;
    private final QueryExtractor queryExtractor;
    private final CollectionFramework collectionFramework;

    public TraceRawService(
            @Nullable TraceQueryService traceQueryService,
            @Nullable TraceDataStorage traceDataStorage,
            QueryExtractor queryExtractor) {
        this.traceQueryService = traceQueryService;
        this.traceDataStorage = traceDataStorage;
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
        return traceQueryService != null;
    }

    public TraceRawResponse getTraces(int limit, TraceCaptureMode mode) {
        if (traceQueryService == null) {
            return TraceRawResponse.empty(collectionFramework);
        }

        List<TraceData> traces = traceQueryService.getTraces(limit, mode);
        List<TraceRawData> rawTraces = traces.stream()
                .map(this::mapToRawData)
                .toList();

        TraceRawSummary summary = calculateSummary(rawTraces);

        return new TraceRawResponse(collectionFramework, summary, rawTraces);
    }

    public Optional<TraceRawData> getTrace(String traceId) {
        if (traceQueryService == null) {
            return Optional.empty();
        }

        return traceQueryService.getTrace(traceId)
                .map(this::mapToRawData);
    }

    private TraceRawData mapToRawData(TraceData traceData) {
        List<QueryInfo> queries = queryExtractor.extract(traceData);
        List<TraceLog> logs = List.of();
        HttpExchange httpExchange = null;

        if (traceDataStorage != null) {
            Optional<TraceDataBundle> bundleOpt = traceDataStorage.getTrace(traceData.traceId());

            if (bundleOpt.isPresent()) {
                TraceDataBundle bundle = bundleOpt.get();

                logs = bundle.logs().stream()
                        .map(e -> new TraceLog(
                                e.spanId(),
                                e.timestamp(),
                                e.level(),
                                e.loggerName(),
                                e.message(),
                                e.threadName()
                        ))
                        .toList();

                RequestCompletedEvent reqEvent = bundle.request();
                if (reqEvent != null) {
                    httpExchange = HttpExchange.from(reqEvent);
                }
            }
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
                traceData.spans(),
                logs,
                queries,
                httpExchange
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
