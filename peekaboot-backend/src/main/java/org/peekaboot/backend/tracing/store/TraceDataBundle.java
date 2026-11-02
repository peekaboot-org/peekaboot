package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

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
 * out via {@link #snapshot()}. That table is pruned as spans are evicted (see
 * {@link #evictOldest}), so it stays bounded by the {@code maxSpans} cap rather than growing
 * for the trace's whole life.
 */
public class TraceDataBundle {

    private final String traceId;
    private final int maxSpans;
    private final int maxLogs;
    private final TraceRun run;
    private final Object spansLock = new Object();
    private final Map<String, SpanData> spansById = new LinkedHashMap<>();
    private final Map<String, String> parentRedirects = new HashMap<>();
    /** Reverse index of {@link #parentRedirects}, so an evicted survivor's redirects can be pruned. */
    private final Map<String, List<String>> redirectsPointingAt = new HashMap<>();

    private final Map<String, List<String>> childrenByParentId = new HashMap<>();
    /** Real spans the cap evicted. A folded duplicate was never stored, so it never counts. */
    private int droppedSpans;
    /** Never cleared: a root the span cap evicts later leaves the trace complete. */
    private boolean parentlessSpanStored;
    // Classification signals, maintained span by span in store() so InMemoryTraceStore's
    // classify() never has to copy and sort the whole trace per arriving span. fullWindow gives
    // the trace's start and the all-async fallback. A folded-away duplicate carries its
    // survivor's name and tags and lies inside it, so skipping it changes nothing.
    private boolean hasErrorSpan;
    private boolean hasServerErrorSpan;
    private Window fullWindow = Window.EMPTY;
    private final AsyncMembership asyncMembership = new AsyncMembership();
    /** Guarded by {@code spansLock}, so the snapshot reads it with the Errors bucket it filed the trace in. */
    private boolean hasErrorLog;

    private final List<LogCapturedEvent> logs = Collections.synchronizedList(new ArrayList<>());
    /** Guarded by {@code logs}, the lock the trim already takes. */
    private int droppedLogs;

    private volatile RequestCompletedEvent request;

    /**
     * @param maxSpans the most deduplicated spans kept, oldest evicted first
     * @param maxLogs  the most logs kept, oldest trimmed first
     * @param run      the run the store was in when it first received anything for the trace
     */
    TraceDataBundle(String traceId, int maxSpans, int maxLogs, TraceRun run) {
        this.traceId = traceId;
        this.maxSpans = maxSpans;
        this.maxLogs = maxLogs;
        this.run = run;
    }

    public String traceId() {
        return traceId;
    }

    /**
     * Folds {@code span} into the bundle, collapsing it against already-stored spans (or vice
     * versa) before the {@code maxSpans} cap is checked, so the cap counts real spans rather
     * than double-instrumented duplicates. See the class Javadoc for why this has to check
     * both directions.
     *
     * <p>{@code fileInBuckets} runs before the span lock is released, so no reader sees the span
     * without the buckets it files the trace in. It may take a bucket's monitor; nothing that holds
     * one waits for this lock (see {@link InMemoryTraceStore}).
     */
    public void addSpan(SpanData span, Consumer<TraceDataBundle> fileInBuckets) {
        synchronized (spansLock) {
            if (!isDuplicateOfStoredParent(span)) {
                store(span);
                placeInAsyncSubtrees(span, absorbDuplicateChildrenOf(span));
            }
            asyncMembership.refresh(spansById.values());
            if (spansById.size() > maxSpans) {
                int excess = spansById.size() - maxSpans;
                droppedSpans += excess;
                evictOldest(excess);
            }
            fileInBuckets.accept(this);
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
            // the duplicate's stored children resolve to parent, so they share its subtree
            if (asyncMembership.contains(parent.spanId())) {
                joinDescendants(span.spanId());
            }
            return true;
        }
        return false;
    }

    private void store(SpanData span) {
        spansById.put(span.spanId(), span);
        if (span.hasError()) {
            hasErrorSpan = true;
        }
        if (HttpSpanTags.isServerError(span.kind(), span.tags())) {
            hasServerErrorSpan = true;
        }
        if (span.parentId() == null) {
            parentlessSpanStored = true;
        }
        fullWindow = fullWindow.including(span);
        if (span.parentId() != null) {
            childrenByParentId
                    .computeIfAbsent(span.parentId(), k -> new ArrayList<>())
                    .add(span.spanId());
        }
    }

    /**
     * Places the arriving span inside or outside an async subtree. A span that enters one takes
     * its stored descendants along: spans are stored child-before-parent (see the class Javadoc),
     * so they have been counting as synchronous until now. Runs after
     * {@link #absorbDuplicateChildrenOf}, so a folded duplicate's children are reached through
     * the redirect.
     */
    private void placeInAsyncSubtrees(SpanData span, List<SpanData> absorbedDuplicates) {
        if (AsyncTaskMarker.isMarked(span.tags()) || asyncMembership.contains(resolve(span.parentId()))) {
            asyncMembership.enter(span.spanId(), absorbedDuplicates);
            joinDescendants(span.spanId());
            return;
        }
        asyncMembership.foldSynchronous(span);
    }

    /**
     * Moves the stored spans below {@code spanId} into its subtree. Stops at spans already in
     * one, whose own descendants joined with them, so each span is walked once per trace.
     */
    private void joinDescendants(String spanId) {
        Deque<String> pending = new ArrayDeque<>();
        pending.add(spanId);
        while (!pending.isEmpty()) {
            for (String childId : resolvedChildIdsOf(pending.remove())) {
                SpanData child = spansById.get(childId);
                if (child != null && asyncMembership.join(child)) {
                    pending.add(childId);
                }
            }
        }
    }

    /**
     * The ids of the stored spans whose resolved parent is {@code spanId}. {@link #childrenByParentId}
     * is keyed on the raw parent id, so a child naming a folded-away duplicate of {@code spanId}
     * sits under the duplicate's id, and {@link #redirectsPointingAt} lists every such duplicate.
     */
    private List<String> resolvedChildIdsOf(String spanId) {
        List<String> childIds = new ArrayList<>(childrenByParentId.getOrDefault(spanId, List.of()));
        for (String duplicateId : redirectsPointingAt.getOrDefault(spanId, List.of())) {
            childIds.addAll(childrenByParentId.getOrDefault(duplicateId, List.of()));
        }
        return childIds;
    }

    /**
     * The span just stored may be the real span whose duplicate already arrived as one of
     * its direct children - the expected ordering, since a duplicate ends (and so exports)
     * before the real span containing it does. Folds any such child away, redirects it here
     * and returns it, so the survivor's placement can settle whether its time stays synchronous.
     */
    private List<SpanData> absorbDuplicateChildrenOf(SpanData survivor) {
        List<String> children = childrenByParentId.get(survivor.spanId());
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<SpanData> absorbed = new ArrayList<>();
        Iterator<String> it = children.iterator();
        while (it.hasNext()) {
            String childId = it.next();
            SpanData candidate = spansById.get(childId);
            if (candidate != null && SpanDuplicateMatcher.isDuplicate(candidate, survivor)) {
                spansById.remove(childId);
                recordRedirect(childId, survivor.spanId());
                asyncMembership.forget(candidate);
                absorbed.add(candidate);
                it.remove();
            }
        }
        return absorbed;
    }

    /**
     * Records that {@code removedId} folded into {@code survivorId}, maintaining
     * {@link #redirectsPointingAt} alongside {@link #parentRedirects} so the entry can be
     * pruned in {@link #evictOldest} once {@code survivorId} itself is evicted.
     */
    private void recordRedirect(String removedId, String survivorId) {
        parentRedirects.put(removedId, survivorId);
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).add(removedId);
        retargetChainedRedirects(removedId, survivorId);
    }

    /**
     * {@code removedId} may itself have been an earlier fold's survivor - i.e. other ids
     * already redirect to it (triple-nested duplicates; does not occur in production, but
     * the fold must stay correct if it ever did). A folded-away id, unlike a real span, is
     * never evicted, so entries keyed on it would never be pruned. Re-point them directly
     * at {@code survivorId} so they are pruned together with it once it is evicted.
     */
    private void retargetChainedRedirects(String removedId, String survivorId) {
        List<String> chained = redirectsPointingAt.remove(removedId);
        if (chained == null || chained.isEmpty()) {
            return;
        }
        chained.forEach(id -> parentRedirects.put(id, survivorId));
        redirectsPointingAt.computeIfAbsent(survivorId, k -> new ArrayList<>()).addAll(chained);
    }

    /**
     * Follows the redirect chain for a (possibly folded-away) span id to the span it
     * ultimately survived as. Returns {@code spanId} unchanged if it was never redirected.
     */
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
            asyncMembership.evict(evicted);
        }
    }

    /** Redirects into an evicted span can never resolve again. */
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

    /**
     * An ERROR line raises the failure flag and runs {@code fileInErrors} under the span lock, the
     * one {@link #snapshot()} reads the flag under. Any other line takes no span lock, so a logging
     * thread pays for it only when it logs an error.
     */
    public void addLog(LogCapturedEvent log, Consumer<TraceDataBundle> fileInErrors) {
        if (log.isError()) {
            synchronized (spansLock) {
                hasErrorLog = true;
                fileInErrors.accept(this);
            }
        }
        logs.add(log);
        // cheap fast path; the trim re-checks under the lock
        if (logs.size() > maxLogs) {
            synchronized (logs) {
                if (logs.size() > maxLogs) {
                    int excess = logs.size() - maxLogs;
                    logs.subList(0, excess).clear();
                    droppedLogs += excess;
                }
            }
        }
    }

    /** The logs kept and the count the cap trimmed, read under the trim's lock so the two describe the same moment. */
    public CapturedLogs capturedLogs() {
        synchronized (logs) {
            return new CapturedLogs(new ArrayList<>(logs), droppedLogs);
        }
    }

    public void setRequest(RequestCompletedEvent request) {
        this.request = request;
    }

    /** Test-only: pins a bound on the redirect table's growth without reflection. */
    int parentRedirectCountForTesting() {
        synchronized (spansLock) {
            return parentRedirects.size();
        }
    }

    /**
     * Everything a reader needs about the spans, taken under one lock so the list, the root,
     * the completeness, the failure, the window and the dropped count describe the same moment.
     */
    public TraceData snapshot() {
        synchronized (spansLock) {
            List<SpanData> spans = spansById.values().stream()
                    .map(this::withResolvedParent)
                    .sorted(Comparator.comparingLong(SpanData::creationOrder))
                    .toList();
            // the root is one of the resolved copies, so the mapper's equality skip finds it in the list
            SpanData root = rootOf(spans);
            boolean complete = isComplete(root);
            return new TraceData(
                    traceId,
                    run.startedAt(),
                    fullWindow.start(),
                    synchronousWindow(),
                    root,
                    complete,
                    isInterrupted(complete),
                    failed(),
                    spans,
                    asyncMembership.members(),
                    droppedSpans);
        }
    }

    /**
     * Rewrites a stored span's parentId if it still points at a span folded away since -
     * must run under {@code spansLock}, as it consults {@link #parentRedirects}.
     */
    private SpanData withResolvedParent(SpanData span) {
        if (span.parentId() == null) {
            return span;
        }
        String resolvedParentId = resolve(span.parentId());
        return resolvedParentId.equals(span.parentId()) ? span : span.withParentId(resolvedParentId);
    }

    /**
     * The root, the fragment flag, the async entry spans and the facts the listing's filters read,
     * under one lock so they describe the same moment. Read without copying, so the listing can
     * classify and filter every bundle in a bucket without a snapshot per bundle.
     */
    public ListingRoots listingRoots() {
        synchronized (spansLock) {
            SpanData root = rootOf(spansById.values());
            boolean complete = isComplete(root);
            return new ListingRoots(
                    root,
                    !complete && !spansById.isEmpty(),
                    asyncEntrySpans(),
                    fullWindow.start(),
                    synchronousWindow(),
                    complete,
                    isInterrupted(complete),
                    failed());
        }
    }

    private boolean isComplete(@Nullable SpanData root) {
        return parentlessSpanStored || root != null;
    }

    /** An incomplete trace of an ended run: the process its root belonged to has stopped. */
    private boolean isInterrupted(boolean complete) {
        return !complete && run.ended();
    }

    /**
     * The span the tree hangs from: the earliest-created stored span without a parent, else the
     * earliest-created one continuing a remote caller's trace, its parent remote and not stored.
     * None while neither is stored. Spans export as they end and a parent ends after its children,
     * so that is a request still running, or a complete one whose root the span cap evicted
     * (see {@link TraceData#complete}). A remote parent can be this application's own client
     * span, when it called itself; it is stored once it ends, and from then on the span below it
     * roots nothing. The one definition of the root, over stored spans or their resolved copies;
     * must run under {@code spansLock}, as it consults {@link #parentRedirects}.
     */
    private @Nullable SpanData rootOf(Collection<SpanData> spans) {
        SpanData parentless = null;
        SpanData continuingCaller = null;
        for (SpanData span : spans) {
            if (span.parentId() == null) {
                parentless = earlier(parentless, span);
            } else if (span.remoteParent() && !spansById.containsKey(resolve(span.parentId()))) {
                continuingCaller = earlier(continuingCaller, span);
            }
        }
        return parentless != null ? parentless : continuingCaller;
    }

    private static SpanData earlier(@Nullable SpanData current, SpanData candidate) {
        return current == null || candidate.creationOrder() < current.creationOrder() ? candidate : current;
    }

    /** Whether any span stored failed or answered 5xx, or any log captured was an ERROR; maintained as each arrives, no span copy. */
    boolean hasError() {
        synchronized (spansLock) {
            return failed();
        }
    }

    /** The Errors rule; must run under {@code spansLock}. */
    private boolean failed() {
        return hasErrorSpan || hasServerErrorSpan || hasErrorLog;
    }

    /** The window every span stored so far has covered, async ones included; null before a timed span. */
    Duration spanWindow() {
        synchronized (spansLock) {
            return fullWindow.duration();
        }
    }

    /**
     * The window the trace's non-async spans have covered - the duration the listing, the
     * detail header and the Slow classification all read, so background work a caller never
     * waited for does not lengthen the work that triggered it. Falls back to
     * {@link #spanWindow()} for a bundle whose every span is async, where that work is the
     * whole trace and its own duration is the honest answer. What may lower it is in "Async
     * Subtree Timing" in docs/ARCHITECTURE.md.
     */
    public Duration synchronousWindow() {
        synchronized (spansLock) {
            Duration synchronous = asyncMembership.syncWindow().duration();
            return synchronous != null ? synchronous : fullWindow.duration();
        }
    }

    /**
     * The spans that start an async subtree: an async span whose resolved parent is not itself
     * async. Read off the maintained set, with no tree walk, and nothing at all for a bundle
     * without async spans; must run under {@code spansLock}.
     */
    private List<SpanData> asyncEntrySpans() {
        if (asyncMembership.isEmpty()) {
            return List.of();
        }
        List<SpanData> entries = new ArrayList<>();
        for (SpanData stored : spansById.values()) {
            if (asyncMembership.contains(stored.spanId()) && !asyncMembership.contains(resolve(stored.parentId()))) {
                entries.add(stored);
            }
        }
        return entries;
    }

    public RequestCompletedEvent request() {
        return request;
    }
}
