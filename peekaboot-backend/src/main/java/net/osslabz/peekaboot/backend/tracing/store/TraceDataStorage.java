package net.osslabz.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.SpanDataEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central storage for all trace data. Receives events via Spring's event mechanism
 * and stores raw span data, logs, and request info in trace bundles.
 */
public class TraceDataStorage {

    private static final int DEFAULT_MAX_TRACES = 1000;
    // keep in sync with PeekabootTracingProperties.maxSpansPerTrace
    private static final int DEFAULT_MAX_SPANS_PER_TRACE = 100;
    private static final Duration DEFAULT_EXPIRE = Duration.ofMinutes(30);

    private final Cache<String, TraceDataBundle> cache;
    private final int maxSpansPerTrace;
    private final AtomicLong spanCounter = new AtomicLong(0);

    public TraceDataStorage() {
        this(DEFAULT_MAX_TRACES, DEFAULT_MAX_SPANS_PER_TRACE, DEFAULT_EXPIRE);
    }

    public TraceDataStorage(int maxTraces, int maxSpansPerTrace, Duration expireAfter) {
        this.maxSpansPerTrace = maxSpansPerTrace;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .expireAfterWrite(expireAfter)
                .build();
    }

    public long nextCreationOrder() {
        return spanCounter.incrementAndGet();
    }

    @EventListener
    public void onSpanData(SpanDataEvent event) {
        if (event == null || event.spanData() == null) {
            return;
        }
        String traceId = event.spanData().traceId();
        cache.get(traceId, TraceDataBundle::new).addSpan(event.spanData(), maxSpansPerTrace);
    }

    @EventListener
    public void onLogCaptured(LogCapturedEvent event) {
        if (event == null) {
            return;
        }
        cache.get(event.traceId(), TraceDataBundle::new).addLog(event);
    }

    @EventListener
    public void onRequestCompleted(RequestCompletedEvent event) {
        if (event == null) {
            return;
        }
        cache.get(event.traceId(), TraceDataBundle::new).setRequest(event);
    }

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

    public void clear() {
        cache.invalidateAll();
    }

    public void cleanUp() {
        cache.cleanUp();
    }
}
