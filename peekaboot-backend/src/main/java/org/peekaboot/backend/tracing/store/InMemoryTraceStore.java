package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * In-memory {@link TraceStore}: three insertion-ordered buckets, each capped at its own size
 * and evicting its oldest trace once full. Each bucket remembers when a trace entered it, in
 * span export order, so the listing can place rows that arrive after their trace did. A log line
 * written outside any trace goes to a ring of its own beside them, the newest
 * {@code max-untraced-logs}.
 *
 * <p>A trace is filed in its buckets under its bundle's span lock, in the same step that stores the
 * span or the ERROR line that files it, so a reader that maps a trace and then reads its buckets
 * finds every bucket the mapped spans put it in. The locks nest in one order: a bundle's span lock,
 * then All's monitor, then Errors' or Slow's. Nothing holds a bucket's monitor while it waits for a
 * bundle, and nothing holds Errors' or Slow's while it waits for All's, so the nesting cannot
 * deadlock.
 */
public class InMemoryTraceStore implements TraceStore {

    private static final List<TraceBucket> QUALIFYING_BUCKETS = List.of(TraceBucket.ERRORS, TraceBucket.SLOW);
    /** Drawn once per JVM, so the next run numbers apart from this one. */
    private static final AtomicLong NEXT_NUMBERING =
            new AtomicLong(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));

    private final int maxTraces;
    private final int maxSpansPerTrace;
    private final long slowTraceThresholdMs;
    private final int maxLogsPerTrace;
    private final UntracedLogRing untracedLogs;
    private final Map<TraceBucket, Map<String, ListedTrace>> buckets = new EnumMap<>(TraceBucket.class);
    private final AtomicLong newestSpanOrder = new AtomicLong();
    private final AtomicLong listingSequence = new AtomicLong();
    private final AtomicLong logSequence = new AtomicLong();
    private volatile TraceRun run = new TraceRun(null);
    /** Added to each span's creation order, so every run's spans sort after every earlier run's. */
    private volatile long spanOrderBase;
    /** One up per store, so no two stores in one JVM share a numbering. */
    private final long numbering = NEXT_NUMBERING.getAndIncrement();
    /** Set while a snapshot replays (see {@link TraceStore#holdAll}). */
    private volatile boolean allHeld;

    public InMemoryTraceStore(PeekabootTracingProperties properties) {
        this.maxTraces = properties.getMaxTraces();
        this.maxSpansPerTrace = properties.getMaxSpansPerTrace();
        this.slowTraceThresholdMs = properties.getSlowTraceThresholdMs();
        this.maxLogsPerTrace = properties.getMaxLogsPerTrace();
        this.untracedLogs = new UntracedLogRing(properties.getMaxUntracedLogs());
        buckets.put(TraceBucket.ALL, boundedMap(maxTraces, () -> allHeld));
        buckets.put(TraceBucket.ERRORS, boundedMap(properties.getMaxErrorTraces(), () -> false));
        buckets.put(TraceBucket.SLOW, boundedMap(properties.getMaxSlowTraces(), () -> false));
    }

    private static Map<String, ListedTrace> boundedMap(int maxEntries, BooleanSupplier held) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ListedTrace> eldest) {
                return size() > maxEntries && !held.getAsBoolean();
            }
        });
    }

    @Override
    public void addSpan(SpanData span) {
        SpanData numbered = span.numberedAfter(spanOrderBase);
        newestSpanOrder.accumulateAndGet(numbered.creationOrder(), Math::max);
        TraceDataBundle bundle = resolveBundle(numbered.traceId());
        bundle.addSpan(numbered, this::classify);
    }

    /** A line without a trace id goes to the untraced ring, and into no bucket whatever its level. */
    @Override
    public void addLog(LogCapturedEvent log) {
        LogCapturedEvent stored = log.withSequence(logSequence.incrementAndGet());
        if (stored.traceId() == null) {
            untracedLogs.add(stored);
            return;
        }
        TraceDataBundle bundle = resolveBundle(stored.traceId());
        // logs never affect slow classification and can only ever add a trace to the
        // Errors bucket, never remove it, so a full classify() pass is unnecessary here.
        bundle.addLog(stored, filed -> listIn(TraceBucket.ERRORS, filed));
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

    /** Called before live input reaches the store, so nothing else writes meanwhile (see {@link TraceStore#beginRun}). */
    @Override
    public void beginRun(@Nullable Instant startedAt) {
        TraceRun ending = run;
        spanOrderBase = newestSpanOrder.get();
        run = new TraceRun(startedAt);
        ending.end();
    }

    @Override
    public void markRunStarted(Instant startedAt) {
        run.markStarted(startedAt);
    }

    @Override
    public void restoreRemnant(String traceId, TraceRemnant remnant) {
        resolveBundle(traceId).restore(remnant);
    }

    @Override
    public void holdAll(boolean held) {
        allHeld = held;
        if (held) {
            return;
        }
        Map<String, ListedTrace> all = buckets.get(TraceBucket.ALL);
        synchronized (all) {
            Iterator<String> oldestFirst = all.keySet().iterator();
            while (all.size() > maxTraces) {
                oldestFirst.next();
                oldestFirst.remove();
            }
        }
    }

    /** The traces are looked up before the bucket's monitor is taken, which nests in no other. */
    @Override
    public void restoreBucket(TraceBucket bucket, List<String> traceIds) {
        List<TraceDataBundle> listing =
                traceIds.stream().map(this::getTrace).flatMap(Optional::stream).toList();
        Map<String, ListedTrace> listed = buckets.get(bucket);
        synchronized (listed) {
            listed.clear();
            listing.forEach(bundle -> listed.put(bundle.traceId(), listedNow(bundle)));
        }
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
                                .orElseGet(() -> new TraceDataBundle(id, maxSpansPerTrace, maxLogsPerTrace, run))))
                .bundle();
    }

    /** Stamped inside the bucket's lock, so a bucket's insertion order and its stamps agree. */
    private void listIn(TraceBucket bucket, TraceDataBundle bundle) {
        buckets.get(bucket).computeIfAbsent(bundle.traceId(), id -> listedNow(bundle));
    }

    private ListedTrace listedNow(TraceDataBundle bundle) {
        return new ListedTrace(bundle, newestSpanOrder.get(), listingSequence.incrementAndGet());
    }

    @Override
    public long numbering() {
        return numbering;
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

    @Override
    public Set<TraceBucket> bucketsOf(String traceId) {
        Set<TraceBucket> listing = EnumSet.noneOf(TraceBucket.class);
        for (TraceBucket bucket : QUALIFYING_BUCKETS) {
            if (buckets.get(bucket).containsKey(traceId)) {
                listing.add(bucket);
            }
        }
        return listing;
    }

    @Override
    public List<LogCapturedEvent> untracedLogs() {
        return untracedLogs.logs();
    }

    /** Each bucket is read under its own lock, so a trace moving between buckets meanwhile is named once or not at all. */
    @Override
    public List<TraceDataBundle> storedTraces() {
        Map<String, TraceDataBundle> distinct = new LinkedHashMap<>();
        for (Map<String, ListedTrace> bucket : buckets.values()) {
            for (ListedTrace listed : newestFirst(bucket, Integer.MAX_VALUE)) {
                distinct.putIfAbsent(listed.bundle().traceId(), listed.bundle());
            }
        }
        return List.copyOf(distinct.values());
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
     * the one it was admitted by. Runs under the bundle's span lock (see
     * {@link TraceDataBundle#addSpan(SpanData, java.util.function.Consumer)}).
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

    /** Test-only: the monitor a bucket's reads and writes synchronize on, so a test can hold a writer at its bucket step. */
    Object bucketLockForTesting(TraceBucket bucket) {
        return buckets.get(bucket);
    }
}
