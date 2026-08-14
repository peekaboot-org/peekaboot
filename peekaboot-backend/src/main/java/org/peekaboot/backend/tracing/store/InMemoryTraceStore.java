package org.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

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
    // keep in sync with PeekabootTracingProperties.maxLogsPerTrace
    private static final int DEFAULT_MAX_LOGS_PER_TRACE = 500;

    private final Cache<String, TraceDataBundle> cache;
    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final int maxLogsPerTrace;
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
        this(maxTraces, maxSpansPerTrace, expireAfter,
                maxErrorTraces, maxSlowTraces, slowTraceThresholdMs, DEFAULT_MAX_LOGS_PER_TRACE);
    }

    public InMemoryTraceStore(int maxTraces, int maxSpansPerTrace, Duration expireAfter,
                              int maxErrorTraces, int maxSlowTraces, long slowTraceThresholdMs,
                              int maxLogsPerTrace) {
        this.maxSpansPerTrace = maxSpansPerTrace;
        this.slowTraceThresholdMs = slowTraceThresholdMs;
        this.maxLogsPerTrace = maxLogsPerTrace;
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
        TraceDataBundle bundle = resolveBundle(span.traceId());
        bundle.addSpan(span, maxSpansPerTrace);
        classify(bundle);
    }

    @Override
    public void addLog(LogCapturedEvent log) {
        TraceDataBundle bundle = resolveBundle(log.traceId());
        bundle.addLog(log, maxLogsPerTrace);
        // logs never affect slow classification and can only ever add a trace to
        // errorTraces, never remove it — so a full classify() pass is unnecessary here.
        if (!errorTraces.containsKey(bundle.traceId()) && "ERROR".equalsIgnoreCase(log.level())) {
            errorTraces.putIfAbsent(bundle.traceId(), bundle);
        }
    }

    @Override
    public void setRequest(RequestCompletedEvent request) {
        TraceDataBundle bundle = resolveBundle(request.traceId());
        bundle.setRequest(request);
        // the request event affects neither error nor slow membership under the
        // current classification rules (those depend only on spans + logs), so no
        // classify() call is needed here.
    }

    /**
     * Resolves the bundle for a trace id, reusing one retained by a bucket if the
     * All cache has already evicted it — avoids creating a diverging copy for
     * late-arriving events.
     */
    private TraceDataBundle resolveBundle(String traceId) {
        return cache.get(traceId, id -> {
            TraceDataBundle retained = errorTraces.get(id);
            if (retained == null) {
                retained = slowTraces.get(id);
            }
            return retained != null ? retained : new TraceDataBundle(id);
        });
    }

    @Override
    public Optional<TraceDataBundle> getTrace(String traceId) {
        TraceDataBundle bundle = cache.getIfPresent(traceId);
        if (bundle == null) {
            bundle = errorTraces.get(traceId);
        }
        if (bundle == null) {
            bundle = slowTraces.get(traceId);
        }
        return Optional.ofNullable(bundle);
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

    @Override
    public void clear() {
        cache.invalidateAll();
        errorTraces.clear();
        slowTraces.clear();
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }
}
