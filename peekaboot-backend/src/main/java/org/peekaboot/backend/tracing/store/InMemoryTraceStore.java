package org.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/** In-memory {@link TraceStore} backed by a bounded Caffeine cache. */
public class InMemoryTraceStore implements TraceStore {

    /** How long a trace stays in the All bucket; not configurable, owned here and reused by the auto-configuration. */
    public static final Duration DEFAULT_EXPIRE = Duration.ofMinutes(30);

    private final Cache<String, TraceDataBundle> cache;
    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final int maxLogsPerTrace;
    private final Map<String, TraceDataBundle> errorTraces;
    private final Map<String, TraceDataBundle> slowTraces;
    private final AtomicLong spanCounter = new AtomicLong(0);

    /** Bucket caps, slow-trace threshold and log cap at their {@link PeekabootTracingProperties} defaults. */
    public InMemoryTraceStore(int maxTraces, int maxSpansPerTrace, Duration expireAfter) {
        this(maxTraces, maxSpansPerTrace, expireAfter, new PeekabootTracingProperties());
    }

    private InMemoryTraceStore(
            int maxTraces, int maxSpansPerTrace, Duration expireAfter, PeekabootTracingProperties defaults) {
        this(
                maxTraces,
                maxSpansPerTrace,
                expireAfter,
                defaults.getMaxErrorTraces(),
                defaults.getMaxSlowTraces(),
                defaults.getSlowTraceThresholdMs(),
                defaults.getMaxLogsPerTrace());
    }

    public InMemoryTraceStore(
            int maxTraces,
            int maxSpansPerTrace,
            Duration expireAfter,
            int maxErrorTraces,
            int maxSlowTraces,
            long slowTraceThresholdMs,
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
        if (!errorTraces.containsKey(bundle.traceId()) && log.isError()) {
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
            case ALL ->
                cache.asMap().values().stream()
                        .sorted(Comparator.comparingLong(TraceDataBundle::createdAt)
                                .reversed())
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
                || bundle.logs().stream().anyMatch(LogCapturedEvent::isError);
    }

    private boolean isSlow(TraceDataBundle bundle) {
        TraceData traceData = TraceData.fromSpans(bundle.traceId(), bundle.spans());
        return traceData.duration() != null && traceData.duration().toMillis() >= slowTraceThresholdMs;
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        errorTraces.clear();
        slowTraces.clear();
    }

    /**
     * Test hook: runs the All cache's pending maintenance so that a TTL eviction becomes
     * observable synchronously instead of on Caffeine's own schedule.
     */
    public void cleanUp() {
        cache.cleanUp();
    }
}
