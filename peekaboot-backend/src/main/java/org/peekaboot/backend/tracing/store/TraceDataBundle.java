package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/**
 * Bundle of all data collected for a single trace: spans, logs, and request info.
 *
 * <p>Spans are deduplicated as they are written (see {@link #addSpan}), not when the trace
 * is later read, so the per-trace span cap counts real work rather than the
 * double-instrumented artifacts {@link SpanDuplicateMatcher} identifies. This matters because
 * spans arrive one at a time, in the order the OTel {@code BatchSpanProcessor} exports them -
 * which, for causally nested spans, is child-before-parent - a parent span's duration fully
 * contains its children's, so a child always ends, and so exports, before the ancestor
 * containing it does. A duplicate span is a direct child of the real span it duplicates, so
 * in the common case the duplicate arrives *before* the real span it needs to be compared
 * against even exists in the bundle yet.
 *
 * <p>The fold below therefore checks both directions on every insertion: does the arriving
 * span turn out to duplicate its own already-stored parent (the uncommon ordering), and does
 * an already-stored span turn out to duplicate the span that just arrived (the expected
 * ordering). A folded-away duplicate's id is kept in a redirect table so a later span whose
 * stated parent was already folded away - a grandchild of the duplicate, which arrives before
 * the duplicate does - still resolves to the surviving ancestor when the trace is read back
 * out via {@link #spans()}. That table is pruned as spans are evicted (see
 * {@link #evictOldest}), so it stays bounded by the {@code maxSpans} cap rather than growing
 * for the trace's whole life.
 */
public class TraceDataBundle {

    private final String traceId;
    private final Object spansLock = new Object();
    private final Map<String, SpanData> spansById = new LinkedHashMap<>();
    private final Map<String, String> parentRedirects = new HashMap<>();
    // reverse index of parentRedirects, pruned in evictOldest so the redirect table stays
    // bounded by currently-retained real spans rather than growing for the trace's whole
    // life under sustained duplicate-producing traffic
    private final Map<String, List<String>> redirectsPointingAt = new HashMap<>();
    private final Map<String, List<String>> childrenByParentId = new HashMap<>();
    private boolean truncated = false;
    // Classification signals, maintained span by span in store() so InMemoryTraceStore's
    // classify() never has to copy and sort the whole trace per arriving span. High-water
    // values, deliberately not recomputed on eviction: bucket membership is add-only, and
    // a folded-away duplicate is an identical twin of its surviving span, so skipping it
    // changes nothing.
    private boolean hasErrorSpan;
    private Instant minSpanStart;
    private Instant maxSpanEnd;
    private volatile boolean hasErrorLog;
    private final List<LogCapturedEvent> logs = Collections.synchronizedList(new ArrayList<>());
    private volatile RequestCompletedEvent request;
    private final long createdAt;

    public TraceDataBundle(String traceId) {
        this(traceId, System::currentTimeMillis);
    }

    /** Clock seam, mirroring RequestCaptureFilter's: deterministic creation ordering for tests. */
    TraceDataBundle(String traceId, LongSupplier clock) {
        this.traceId = traceId;
        this.createdAt = clock.getAsLong();
    }

    public String traceId() {
        return traceId;
    }

    public long createdAt() {
        return createdAt;
    }

    /**
     * Folds {@code span} into the bundle, collapsing it against already-stored spans (or vice
     * versa) before the {@code maxSpans} cap is checked, so the cap counts real spans rather
     * than double-instrumented duplicates. See the class Javadoc for why this has to check
     * both directions.
     */
    public void addSpan(SpanData span, int maxSpans) {
        synchronized (spansLock) {
            if (isDuplicateOfStoredParent(span)) {
                return;
            }
            store(span);
            absorbDuplicateChildrenOf(span);
            if (spansById.size() > maxSpans) {
                truncated = true;
                evictOldest(spansById.size() - maxSpans);
            }
        }
    }

    /**
     * True if {@code span}'s parent is already stored and {@code span} duplicates it - the
     * uncommon ordering, but possible once earlier folds have populated
     * {@link #parentRedirects}. If so, records the redirect instead of storing the span.
     */
    private boolean isDuplicateOfStoredParent(SpanData span) {
        String resolvedParentId = resolve(span.parentId());
        SpanData parent = resolvedParentId != null ? spansById.get(resolvedParentId) : null;
        if (parent != null && SpanDuplicateMatcher.isDuplicate(span, parent)) {
            recordRedirect(span.spanId(), parent.spanId());
            return true;
        }
        return false;
    }

    private void store(SpanData span) {
        spansById.put(span.spanId(), span);
        if (span.hasError()) {
            hasErrorSpan = true;
        }
        if (span.startTime() != null
                && (minSpanStart == null || span.startTime().isBefore(minSpanStart))) {
            minSpanStart = span.startTime();
        }
        if (span.endTime() != null && (maxSpanEnd == null || span.endTime().isAfter(maxSpanEnd))) {
            maxSpanEnd = span.endTime();
        }
        if (span.parentId() != null) {
            childrenByParentId
                    .computeIfAbsent(span.parentId(), k -> new ArrayList<>())
                    .add(span.spanId());
        }
    }

    /**
     * The span just stored may be the real span whose duplicate already arrived as one of
     * its direct children - the expected ordering, since a duplicate ends (and so exports)
     * before the real span containing it does. Folds any such child away and redirects it
     * here.
     */
    private void absorbDuplicateChildrenOf(SpanData survivor) {
        List<String> children = childrenByParentId.get(survivor.spanId());
        if (children == null || children.isEmpty()) {
            return;
        }
        Iterator<String> it = children.iterator();
        while (it.hasNext()) {
            String childId = it.next();
            SpanData candidate = spansById.get(childId);
            if (candidate != null && SpanDuplicateMatcher.isDuplicate(candidate, survivor)) {
                spansById.remove(childId);
                recordRedirect(childId, survivor.spanId());
                it.remove();
            }
        }
    }

    /** Records that {@code removedId} folded into {@code survivorId}, maintaining
     * {@link #redirectsPointingAt} alongside {@link #parentRedirects} so the entry can be
     * pruned in {@link #evictOldest} once {@code survivorId} itself is evicted. */
    private void recordRedirect(String removedId, String survivorId) {
        parentRedirects.put(removedId, survivorId);
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).add(removedId);
        retargetChainedRedirects(removedId, survivorId);
    }

    /** {@code removedId} may itself have been an earlier fold's survivor - i.e. other ids
     * already redirect to it (triple-nested duplicates; does not occur in production, but
     * the fold must stay correct if it ever did). {@code removedId} is now folded away too
     * and, unlike a real span, will never itself be evicted, so those entries would never
     * be pruned if left keyed on it. Re-point them directly at {@code survivorId} so they
     * are pruned together with it once it is evicted. */
    private void retargetChainedRedirects(String removedId, String survivorId) {
        List<String> chained = redirectsPointingAt.remove(removedId);
        if (chained == null || chained.isEmpty()) {
            return;
        }
        chained.forEach(id -> parentRedirects.put(id, survivorId));
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).addAll(chained);
    }

    /** Follows the redirect chain for a (possibly folded-away) span id to the span it
     * ultimately survived as. Returns {@code spanId} unchanged if it was never redirected. */
    private String resolve(String spanId) {
        String current = spanId;
        while (current != null && parentRedirects.containsKey(current)) {
            current = parentRedirects.get(current);
        }
        return current;
    }

    /**
     * Resolves a log's MDC spanId to the span it ultimately survived as, following
     * {@link #parentRedirects} the same way {@link #withResolvedParent} does for spans. Logs
     * arrive synchronously via MDC while the span that folds them (and the resulting redirect)
     * arrives later via the OTel {@code BatchSpanProcessor}, so this must be resolved at read
     * time rather than when the log is stored.
     */
    public String resolveSpanId(String spanId) {
        synchronized (spansLock) {
            return resolve(spanId);
        }
    }

    private void evictOldest(int count) {
        Iterator<Map.Entry<String, SpanData>> it = spansById.entrySet().iterator();
        for (int i = 0; i < count && it.hasNext(); i++) {
            SpanData evicted = it.next().getValue();
            it.remove();
            removeFromParentIndex(evicted);
            childrenByParentId.remove(evicted.spanId());
            pruneRedirectsPointingAt(evicted.spanId());
        }
    }

    /** An evicted real span can no longer be resolved to, so any redirects that folded a
     * duplicate into it are dead weight - drop them rather than let {@link #parentRedirects}
     * grow for the trace's whole life regardless of the {@code maxSpans} cap. */
    private void pruneRedirectsPointingAt(String evictedSpanId) {
        List<String> redirected = redirectsPointingAt.remove(evictedSpanId);
        if (redirected != null) {
            redirected.forEach(parentRedirects::remove);
        }
    }

    private void removeFromParentIndex(SpanData span) {
        if (span.parentId() == null) {
            return;
        }
        List<String> siblings = childrenByParentId.get(span.parentId());
        if (siblings == null) {
            return;
        }
        siblings.remove(span.spanId());
        if (siblings.isEmpty()) {
            childrenByParentId.remove(span.parentId());
        }
    }

    public void addLog(LogCapturedEvent log, int maxLogs) {
        if (log.isError()) {
            hasErrorLog = true;
        }
        logs.add(log);
        if (logs.size() > maxLogs) {
            synchronized (logs) {
                if (logs.size() > maxLogs) {
                    logs.subList(0, logs.size() - maxLogs).clear();
                }
            }
        }
    }

    public void setRequest(RequestCompletedEvent request) {
        this.request = request;
    }

    /** True once the {@code maxSpans} cap in {@link #addSpan} has actually dropped a real
     * (post-deduplication) span - never set merely because duplicate artifacts were folded
     * away. Sticky: a trace that was ever truncated stays marked as such. */
    public boolean truncated() {
        synchronized (spansLock) {
            return truncated;
        }
    }

    /** Test-only: exposes the redirect table's size so a bound on its growth can be pinned
     * without reflection. Package-private - not part of the public API. */
    int parentRedirectCountForTesting() {
        synchronized (spansLock) {
            return parentRedirects.size();
        }
    }

    public List<SpanData> spans() {
        synchronized (spansLock) {
            // copy under spansLock before sorting; streaming spansById directly would race
            // with a concurrent addSpan call mutating it
            return new ArrayList<>(spansById.values())
                    .stream()
                            .map(this::withResolvedParent)
                            .sorted(Comparator.comparingLong(SpanData::creationOrder))
                            .toList();
        }
    }

    /** Rewrites a stored span's parentId if it still points at a span folded away since -
     * must run under {@code spansLock}, as it consults {@link #parentRedirects}. */
    private SpanData withResolvedParent(SpanData span) {
        if (span.parentId() == null) {
            return span;
        }
        String resolvedParentId = resolve(span.parentId());
        return resolvedParentId.equals(span.parentId()) ? span : span.withParentId(resolvedParentId);
    }

    /** Whether any span stored so far carried an error - an incremental read for classification. */
    public boolean hasErrorSpan() {
        synchronized (spansLock) {
            return hasErrorSpan;
        }
    }

    /** Whether any log captured so far was an error. */
    public boolean hasErrorLog() {
        return hasErrorLog;
    }

    /**
     * The wall-clock window the trace's spans have covered so far - the duration the slow
     * classification compares, read off the running min/max instead of copying and
     * sorting the spans. Null while no timed span has arrived.
     */
    public Duration spanWindow() {
        synchronized (spansLock) {
            return minSpanStart == null || maxSpanEnd == null ? null : Duration.between(minSpanStart, maxSpanEnd);
        }
    }

    public List<LogCapturedEvent> logs() {
        return new ArrayList<>(logs);
    }

    public RequestCompletedEvent request() {
        return request;
    }
}
