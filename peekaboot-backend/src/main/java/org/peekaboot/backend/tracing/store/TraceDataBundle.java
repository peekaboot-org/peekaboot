package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundle of all data collected for a single trace: spans, logs, and request info.
 *
 * <p>Spans are deduplicated as they are written (see {@link #addSpan}), not when the trace
 * is later read, so the per-trace span cap counts real work rather than the
 * double-instrumented artifacts {@link SpanDuplicateMatcher} identifies. This matters because
 * spans arrive one at a time, in the order the OTel {@code BatchSpanProcessor} exports them -
 * which, for causally nested spans, is child-before-parent (a span cannot finish, and so
 * cannot export, before the ancestor that contains it). A duplicate span is a direct child of
 * the real span it duplicates, so in the common case the duplicate arrives *before* the real
 * span it needs to be compared against even exists in the bundle yet.
 *
 * <p>The fold below therefore checks both directions on every insertion: does the arriving
 * span turn out to duplicate its own already-stored parent (the uncommon ordering), and does
 * an already-stored span turn out to duplicate the span that just arrived (the expected
 * ordering). A folded-away duplicate's id is kept in a redirect table for the bundle's
 * lifetime, so a later span whose stated parent was already folded away - a grandchild of the
 * duplicate, which arrives before the duplicate does - still resolves to the surviving
 * ancestor when the trace is read back out via {@link #spans()}.
 */
public class TraceDataBundle {

    private final String traceId;
    private final Object spansLock = new Object();
    private final Map<String, SpanData> spansById = new LinkedHashMap<>();
    private final Map<String, String> parentRedirects = new HashMap<>();
    private final Map<String, List<String>> childrenByParentId = new HashMap<>();
    private final List<LogCapturedEvent> logs = Collections.synchronizedList(new ArrayList<>());
    private volatile RequestCompletedEvent request;
    private final long createdAt;

    public TraceDataBundle(String traceId) {
        this.traceId = traceId;
        this.createdAt = System.currentTimeMillis();
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
            parentRedirects.put(span.spanId(), parent.spanId());
            return true;
        }
        return false;
    }

    private void store(SpanData span) {
        spansById.put(span.spanId(), span);
        if (span.parentId() != null) {
            childrenByParentId.computeIfAbsent(span.parentId(), k -> new ArrayList<>()).add(span.spanId());
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
                parentRedirects.put(childId, survivor.spanId());
                it.remove();
            }
        }
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

    private void evictOldest(int count) {
        Iterator<Map.Entry<String, SpanData>> it = spansById.entrySet().iterator();
        for (int i = 0; i < count && it.hasNext(); i++) {
            SpanData evicted = it.next().getValue();
            it.remove();
            removeFromParentIndex(evicted);
            childrenByParentId.remove(evicted.spanId());
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

    public List<SpanData> spans() {
        synchronized (spansLock) {
            // copy under spansLock before sorting; streaming spansById directly would race
            // with a concurrent addSpan call mutating it
            return new ArrayList<>(spansById.values()).stream()
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

    public List<LogCapturedEvent> logs() {
        return new ArrayList<>(logs);
    }

    public RequestCompletedEvent request() {
        return request;
    }
}
