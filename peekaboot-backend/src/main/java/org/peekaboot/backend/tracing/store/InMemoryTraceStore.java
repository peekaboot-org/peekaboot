package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * In-memory {@link TraceStore}: three insertion-ordered buckets, each capped at its own size
 * and evicting its oldest trace once full. Each bucket remembers when a trace entered it, in
 * span export order, so the listing can place rows that arrive after their trace did.
 */
public class InMemoryTraceStore implements TraceStore {

    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final int maxLogsPerTrace;
    private final Map<TraceBucket, Map<String, ListedTrace>> buckets = new EnumMap<>(TraceBucket.class);
    private final AtomicLong newestSpanOrder = new AtomicLong();

    public InMemoryTraceStore(PeekabootTracingProperties properties) {
        this.maxSpansPerTrace = properties.getMaxSpansPerTrace();
        this.slowTraceThresholdMs = properties.getSlowTraceThresholdMs();
        this.maxLogsPerTrace = properties.getMaxLogsPerTrace();
        buckets.put(TraceBucket.ALL, boundedMap(properties.getMaxTraces()));
        buckets.put(TraceBucket.ERRORS, boundedMap(properties.getMaxErrorTraces()));
        buckets.put(TraceBucket.SLOW, boundedMap(properties.getMaxSlowTraces()));
    }

    private static Map<String, ListedTrace> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ListedTrace> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public void addSpan(SpanData span) {
        newestSpanOrder.accumulateAndGet(span.creationOrder(), Math::max);
        TraceDataBundle bundle = resolveBundle(span.traceId());
        bundle.addSpan(span);
        classify(bundle);
    }

    @Override
    public void addLog(LogCapturedEvent log) {
        TraceDataBundle bundle = resolveBundle(log.traceId());
        bundle.addLog(log);
        // logs never affect slow classification and can only ever add a trace to the
        // Errors bucket, never remove it, so a full classify() pass is unnecessary here.
        if (log.isError()) {
            listIn(TraceBucket.ERRORS, bundle);
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
        buckets.values().forEach(bucket -> bucket.remove(traceId));
    }

    /**
     * Resolves the bundle for a trace id, reusing one retained by a bucket if the All
     * bucket has already evicted it - avoids creating a diverging copy for late-arriving
     * events. Such a bucket-retained trace re-enters All, at the newest end, on that event.
     */
    private TraceDataBundle resolveBundle(String traceId) {
        return buckets.get(TraceBucket.ALL)
                .computeIfAbsent(
                        traceId,
                        id -> listedNow(listedAnywhere(id)
                                .map(ListedTrace::bundle)
                                .orElseGet(() -> new TraceDataBundle(id, maxSpansPerTrace, maxLogsPerTrace))))
                .bundle();
    }

    /** Stamped inside the bucket's lock, so a bucket's insertion order and its stamps agree. */
    private void listIn(TraceBucket bucket, TraceDataBundle bundle) {
        buckets.get(bucket).computeIfAbsent(bundle.traceId(), id -> listedNow(bundle));
    }

    private ListedTrace listedNow(TraceDataBundle bundle) {
        return new ListedTrace(bundle, newestSpanOrder.get());
    }

    @Override
    public Optional<TraceDataBundle> getTrace(String traceId) {
        return listedAnywhere(traceId).map(ListedTrace::bundle);
    }

    /** The first bucket listing the trace, in {@link TraceBucket} order: All, then Errors, then Slow. */
    private Optional<ListedTrace> listedAnywhere(String traceId) {
        return buckets.values().stream()
                .map(bucket -> bucket.get(traceId))
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public List<ListedTrace> getTraces(TraceBucket bucket, int limit) {
        return newestFirst(buckets.get(bucket), limit);
    }

    @Override
    public int getTraceCount(TraceBucket bucket) {
        return buckets.get(bucket).size();
    }

    private List<ListedTrace> newestFirst(Map<String, ListedTrace> bucket, int limit) {
        synchronized (bucket) {
            List<ListedTrace> listed = new ArrayList<>(bucket.values());
            Collections.reverse(listed);
            return listed.stream().limit(limit).toList();
        }
    }

    /**
     * Bucket membership after the arriving span. The Errors bucket is add-only - a failure
     * that happened stays one - but the Slow bucket cannot be: the synchronous window a trace
     * was admitted by can fall. A child of an async entry arrives before the entry itself
     * does (see {@link TraceDataBundle}), counts as synchronous until then, and can push the
     * window past the threshold on background work alone; the entry's arrival takes the child
     * into its subtree and the window returns to what the caller waited for. Without the removal
     * below the Slow bucket would keep listing that trace beside a duration disagreeing with
     * the one it was admitted by.
     */
    private void classify(TraceDataBundle bundle) {
        if (bundle.hasError()) {
            listIn(TraceBucket.ERRORS, bundle);
        }
        if (isSlow(bundle)) {
            listIn(TraceBucket.SLOW, bundle);
        } else {
            buckets.get(TraceBucket.SLOW).remove(bundle.traceId());
        }
    }

    private boolean isSlow(TraceDataBundle bundle) {
        Duration window = bundle.synchronousWindow();
        return window != null && window.toMillis() >= slowTraceThresholdMs;
    }
}
