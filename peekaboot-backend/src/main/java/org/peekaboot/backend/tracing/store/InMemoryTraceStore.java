package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/**
 * In-memory {@link TraceStore}: three insertion-ordered buckets, each capped at its own size
 * and evicting its oldest trace once full.
 */
public class InMemoryTraceStore implements TraceStore {

    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final int maxLogsPerTrace;
    private final Map<String, TraceDataBundle> allTraces;
    private final Map<String, TraceDataBundle> errorTraces;
    private final Map<String, TraceDataBundle> slowTraces;

    public InMemoryTraceStore(PeekabootTracingProperties properties) {
        this.maxSpansPerTrace = properties.getMaxSpansPerTrace();
        this.slowTraceThresholdMs = properties.getSlowTraceThresholdMs();
        this.maxLogsPerTrace = properties.getMaxLogsPerTrace();
        this.allTraces = boundedMap(properties.getMaxTraces());
        this.errorTraces = boundedMap(properties.getMaxErrorTraces());
        this.slowTraces = boundedMap(properties.getMaxSlowTraces());
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
        // request data never affects bucket membership
    }

    @Override
    public void discard(String traceId) {
        allTraces.remove(traceId);
        errorTraces.remove(traceId);
        slowTraces.remove(traceId);
    }

    /**
     * Resolves the bundle for a trace id, reusing one retained by a bucket if the All
     * bucket has already evicted it - avoids creating a diverging copy for late-arriving
     * events. Such a bucket-retained trace re-enters All, at the newest end, on that event.
     */
    private TraceDataBundle resolveBundle(String traceId) {
        return allTraces.computeIfAbsent(traceId, id -> {
            TraceDataBundle retained = errorTraces.get(id);
            if (retained == null) {
                retained = slowTraces.get(id);
            }
            return retained != null ? retained : new TraceDataBundle(id);
        });
    }

    @Override
    public Optional<TraceDataBundle> getTrace(String traceId) {
        TraceDataBundle bundle = allTraces.get(traceId);
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
            case ALL -> newestFirst(allTraces, limit);
            case ERRORS -> newestFirst(errorTraces, limit);
            case SLOW -> newestFirst(slowTraces, limit);
        };
    }

    @Override
    public int getTraceCount(TraceBucket bucket) {
        return switch (bucket) {
            case ALL -> allTraces.size();
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

    /** Incremental signals the bundle maintains as events arrive; no span copy or sort per span. */
    private static boolean hasError(TraceDataBundle bundle) {
        return bundle.hasErrorSpan() || bundle.hasErrorLog();
    }

    private boolean isSlow(TraceDataBundle bundle) {
        Duration window = bundle.spanWindow();
        return window != null && window.toMillis() >= slowTraceThresholdMs;
    }

    @Override
    public void clear() {
        allTraces.clear();
        errorTraces.clear();
        slowTraces.clear();
    }
}
