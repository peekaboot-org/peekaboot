package net.osslabz.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory {@link TraceStore} backed by a bounded Caffeine cache. */
public class InMemoryTraceStore implements TraceStore {

    private static final int DEFAULT_MAX_TRACES = 1000;
    // keep in sync with PeekabootTracingProperties.maxSpansPerTrace
    private static final int DEFAULT_MAX_SPANS_PER_TRACE = 100;
    private static final Duration DEFAULT_EXPIRE = Duration.ofMinutes(30);
    // keep in sync with PeekabootTracingProperties defaults
    private static final int DEFAULT_MAX_ERROR_TRACES = 100;
    private static final int DEFAULT_MAX_SLOW_TRACES = 100;
    private static final long DEFAULT_SLOW_TRACE_THRESHOLD_MS = 1000;

    private final Cache<String, TraceDataBundle> cache;
    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final Map<String, TraceDataBundle> errorTraces;
    private final Map<String, TraceDataBundle> slowTraces;
    private final AtomicLong spanCounter = new AtomicLong(0);

    public InMemoryTraceStore() {
        this(DEFAULT_MAX_TRACES, DEFAULT_MAX_SPANS_PER_TRACE, DEFAULT_EXPIRE);
    }

    public InMemoryTraceStore(int maxTraces, int maxSpansPerTrace, Duration expireAfter) {
        this(maxTraces, maxSpansPerTrace, expireAfter,
                DEFAULT_MAX_ERROR_TRACES, DEFAULT_MAX_SLOW_TRACES, DEFAULT_SLOW_TRACE_THRESHOLD_MS);
    }

    public InMemoryTraceStore(int maxTraces, int maxSpansPerTrace, Duration expireAfter,
                              int maxErrorTraces, int maxSlowTraces, long slowTraceThresholdMs) {
        this.maxSpansPerTrace = maxSpansPerTrace;
        this.slowTraceThresholdMs = slowTraceThresholdMs;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .expireAfterWrite(expireAfter)
                .build();
        this.errorTraces = boundedMap(maxErrorTraces);
        this.slowTraces = boundedMap(maxSlowTraces);
    }

    private static Map<String, TraceDataBundle> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TraceDataBundle> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public long nextCreationOrder() {
        return spanCounter.incrementAndGet();
    }

    @Override
    public void addSpan(SpanData span) {
        TraceDataBundle bundle = cache.get(span.traceId(), TraceDataBundle::new);
        bundle.addSpan(span, maxSpansPerTrace);
        classify(bundle);
    }

    @Override
    public void addLog(LogCapturedEvent log) {
        TraceDataBundle bundle = cache.get(log.traceId(), TraceDataBundle::new);
        bundle.addLog(log);
        classify(bundle);
    }

    @Override
    public void setRequest(RequestCompletedEvent request) {
        TraceDataBundle bundle = cache.get(request.traceId(), TraceDataBundle::new);
        bundle.setRequest(request);
        classify(bundle);
    }

    @Override
    public Optional<TraceDataBundle> getTrace(String traceId) {
        return Optional.ofNullable(cache.getIfPresent(traceId));
    }

    @Override
    public List<TraceDataBundle> getTraces(TraceBucket bucket, int limit) {
        return switch (bucket) {
            case ALL -> cache.asMap().values().stream()
                    .sorted(Comparator.comparingLong(TraceDataBundle::createdAt).reversed())
                    .limit(limit)
                    .toList();
            case ERRORS -> newestFirst(errorTraces, limit);
            case SLOW -> newestFirst(slowTraces, limit);
        };
    }

    @Override
    public int getTraceCount(TraceBucket bucket) {
        return switch (bucket) {
            case ALL -> (int) cache.estimatedSize();
            case ERRORS -> errorTraces.size();
            case SLOW -> slowTraces.size();
        };
    }

    private List<TraceDataBundle> newestFirst(Map<String, TraceDataBundle> bucket, int limit) {
        synchronized (bucket) {
            List<TraceDataBundle> bundles = new ArrayList<>(bucket.values());
            Collections.reverse(bundles);
            return bundles.stream().limit(limit).toList();
        }
    }

    private void classify(TraceDataBundle bundle) {
        boolean inErrors = errorTraces.containsKey(bundle.traceId());
        boolean inSlow = slowTraces.containsKey(bundle.traceId());
        if (inErrors && inSlow) {
            return;
        }
        if (!inErrors && hasError(bundle)) {
            errorTraces.putIfAbsent(bundle.traceId(), bundle);
        }
        if (!inSlow && isSlow(bundle)) {
            slowTraces.putIfAbsent(bundle.traceId(), bundle);
        }
    }

    private boolean hasError(TraceDataBundle bundle) {
        return bundle.spans().stream().anyMatch(SpanData::hasError)
                || bundle.logs().stream().anyMatch(log -> "ERROR".equalsIgnoreCase(log.level()));
    }

    private boolean isSlow(TraceDataBundle bundle) {
        TraceData traceData = TraceData.fromSpans(bundle.traceId(), bundle.spans());
        return traceData.duration() != null
                && traceData.duration().toMillis() >= slowTraceThresholdMs;
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
