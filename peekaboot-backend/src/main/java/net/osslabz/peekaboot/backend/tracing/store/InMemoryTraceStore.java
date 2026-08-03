package net.osslabz.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory {@link TraceStore} backed by a bounded Caffeine cache. */
public class InMemoryTraceStore implements TraceStore {

    private static final int DEFAULT_MAX_TRACES = 1000;
    // keep in sync with PeekabootTracingProperties.maxSpansPerTrace
    private static final int DEFAULT_MAX_SPANS_PER_TRACE = 100;
    private static final Duration DEFAULT_EXPIRE = Duration.ofMinutes(30);

    private final Cache<String, TraceDataBundle> cache;
    private final int maxSpansPerTrace;
    private final AtomicLong spanCounter = new AtomicLong(0);

    public InMemoryTraceStore() {
        this(DEFAULT_MAX_TRACES, DEFAULT_MAX_SPANS_PER_TRACE, DEFAULT_EXPIRE);
    }

    public InMemoryTraceStore(int maxTraces, int maxSpansPerTrace, Duration expireAfter) {
        this.maxSpansPerTrace = maxSpansPerTrace;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .expireAfterWrite(expireAfter)
                .build();
    }

    @Override
    public long nextCreationOrder() {
        return spanCounter.incrementAndGet();
    }

    @Override
    public void addSpan(SpanData span) {
        cache.get(span.traceId(), TraceDataBundle::new).addSpan(span, maxSpansPerTrace);
    }

    @Override
    public void addLog(LogCapturedEvent log) {
        cache.get(log.traceId(), TraceDataBundle::new).addLog(log);
    }

    @Override
    public void setRequest(RequestCompletedEvent request) {
        cache.get(request.traceId(), TraceDataBundle::new).setRequest(request);
    }

    @Override
    public Optional<TraceDataBundle> getTrace(String traceId) {
        return Optional.ofNullable(cache.getIfPresent(traceId));
    }

    public Optional<TraceData> getTraceData(String traceId) {
        return getTrace(traceId).map(bundle -> TraceData.fromSpans(traceId, bundle.spans()));
    }

    public List<SpanData> getSpansForTrace(String traceId) {
        return getTrace(traceId)
                .map(TraceDataBundle::spans)
                .orElse(List.of());
    }

    public List<TraceData> getRecentTraceData(int limit) {
        return cache.asMap().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().createdAt(), a.getValue().createdAt()))
                .limit(limit)
                .map(entry -> TraceData.fromSpans(entry.getKey(), entry.getValue().spans()))
                .toList();
    }

    public List<TraceData> getAllTraces() {
        return cache.asMap().entrySet().stream()
                .map(entry -> TraceData.fromSpans(entry.getKey(), entry.getValue().spans()))
                .sorted(Comparator.comparing(TraceData::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public int getTraceCount() {
        return (int) cache.estimatedSize();
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }
}
