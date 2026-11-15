package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * ordering). {@link DuplicateFolds} keeps the redirect table that lets a span whose stated
 * parent was folded away still resolve to the surviving ancestor when the trace is read back
 * out via {@link #snapshot()}.
 *
 * <p>Only datasource twins that both failed or both did not are folded, so a fold never costs
 * the trace a failure and a method that calls itself keeps its nested spans.
 */
public class TraceDataBundle {

    private final String traceId;
    private final int maxSpans;
    private final int maxLogs;
    private final TraceRun run;
    private final Object spansLock = new Object();
    private final Map<String, SpanData> spansById = new LinkedHashMap<>();
    private final DuplicateFolds folds = new DuplicateFolds();
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
    /** A failure only a dropped input showed, carried across a fold; guarded by {@code spansLock}. */
    private boolean droppedInputFailed;
    /** The store filed the trace in Slow and has not taken it out; stays set when Slow's cap evicts it. Guarded by {@code spansLock}. */
    private boolean listedSlow;

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
     *
     * @throws IllegalArgumentException if {@code span} names itself as its parent
     */
    public void addSpan(SpanData span, Consumer<TraceDataBundle> fileInBuckets) {
        refuseOwnParent(span);
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

    /** A span that is its own parent would fold into itself, and resolving it would never leave its redirect. */
    static void refuseOwnParent(SpanData span) {
        if (span.spanId().equals(span.parentId())) {
            throw new IllegalArgumentException("span " + span.spanId() + " names itself as its parent");
        }
    }

    /**
     * True if {@code span}'s parent is already stored and {@code span} duplicates it, in which
     * case it is redirected instead of stored (see {@link DuplicateFolds#foldIntoStoredParent}).
     */
    private boolean isDuplicateOfStoredParent(SpanData span) {
        SpanData parent = folds.foldIntoStoredParent(span, spansById);
        if (parent == null) {
            return false;
        }
        // the duplicate's stored children resolve to parent, so they share its subtree
        if (asyncMembership.contains(parent.spanId())) {
            joinDescendants(span.spanId());
        }
        return true;
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
        folds.stored(span);
    }

    /**
     * Places the arriving span inside or outside an async subtree. A span that enters one takes
     * its stored descendants along: spans are stored child-before-parent (see the class Javadoc),
     * so they have been counting as synchronous until now. Runs after
     * {@link #absorbDuplicateChildrenOf}, so a folded duplicate's children are reached through
     * the redirect.
     */
    private void placeInAsyncSubtrees(SpanData span, List<SpanData> absorbedDuplicates) {
        // a span a fold carried as async enters as it did live, though its entry span was not kept
        if (AsyncTaskMarker.isMarked(span.tags())
                || asyncMembership.contains(folds.resolve(span.parentId()))
                || asyncMembership.contains(span.spanId())) {
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
            for (String childId : folds.resolvedChildIdsOf(pending.remove())) {
                SpanData child = spansById.get(childId);
                if (child != null && asyncMembership.join(child)) {
                    pending.add(childId);
                }
            }
        }
    }

    /**
     * Folds away the direct children that duplicate the span just stored (see
     * {@link DuplicateFolds#absorbDuplicateChildren}) and returns them, so the survivor's
     * placement can settle whether its time stays synchronous.
     */
    private List<SpanData> absorbDuplicateChildrenOf(SpanData survivor) {
        List<SpanData> absorbed = folds.absorbDuplicateChildren(survivor, spansById);
        absorbed.forEach(asyncMembership::forget);
        return absorbed;
    }

    /**
     * Resolves a log's MDC spanId to the span it ultimately survived as, following
     * {@link DuplicateFolds} the same way {@link #withResolvedParent} does for spans. Logs
     * arrive synchronously via MDC while the span that folds them (and the resulting redirect)
     * arrives later via the OTel {@code BatchSpanProcessor}, so this must be resolved at read
     * time rather than when the log is stored.
     */
    public String resolveSpanId(String spanId) {
        synchronized (spansLock) {
            return folds.resolve(spanId);
        }
    }

    private void evictOldest(int count) {
        Iterator<Map.Entry<String, SpanData>> it = spansById.entrySet().iterator();
        for (int i = 0; i < count && it.hasNext(); i++) {
            SpanData evicted = it.next().getValue();
            it.remove();
            folds.evicted(evicted);
            asyncMembership.evict(evicted);
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

    /**
     * What this bundle's dropped inputs left behind. A fold reads it from a store no other thread
     * writes, so the log trim's lock and the span lock are taken one after the other.
     */
    public TraceRemnant remnant() {
        int logsDropped = capturedLogs().droppedLogs();
        synchronized (spansLock) {
            Window evictedSync = asyncMembership.evictedSyncWindow();
            return new TraceRemnant(
                    droppedSpans,
                    logsDropped,
                    parentlessSpanStored,
                    failed(),
                    fullWindow.start(),
                    fullWindow.end(),
                    evictedSync.start(),
                    evictedSync.end(),
                    asyncMembership.members().stream().sorted().toList());
        }
    }

    /** Whether the bundle keeps {@code spanId}, stored or folded into a span it stores. */
    public boolean keepsSpan(String spanId) {
        synchronized (spansLock) {
            return spansById.containsKey(spanId) || folds.foldedAway(spanId);
        }
    }

    /**
     * Whether everything stored hangs from {@code spanId}, a span that is not stored itself: every
     * span descends from it, every log names it or a stored span, and no request is stored. That is
     * what an excluded request leaves under its skipped server span; anything else is another
     * request's. The log trim's lock and the span lock are taken one after the other, as in
     * {@link #remnant()}.
     */
    public boolean liesUnder(String spanId) {
        List<LogCapturedEvent> kept = capturedLogs().logs();
        synchronized (spansLock) {
            return request == null
                    && spansById.values().stream().allMatch(span -> descendsFrom(span, spanId))
                    && kept.stream().allMatch(log -> isWrittenUnder(log, spanId));
        }
    }

    /** Follows the resolved parents through the stored spans; must run under {@code spansLock}. */
    private boolean descendsFrom(SpanData span, String ancestorId) {
        SpanData current = span;
        while (current != null) {
            String parentId = folds.resolve(current.parentId());
            if (ancestorId.equals(parentId)) {
                return true;
            }
            current = spansById.get(parentId);
        }
        return false;
    }

    /** Whether {@code log} names {@code spanId} or a stored span; must run under {@code spansLock}. */
    private boolean isWrittenUnder(LogCapturedEvent log, String spanId) {
        return spanId.equals(log.spanId()) || spansById.containsKey(folds.resolve(log.spanId()));
    }

    /**
     * Seeds what a fold carried for the inputs it did not write; the replay calls it before the trace's
     * kept inputs. It files the trace in no bucket: the snapshot's bucket lines set them all.
     */
    void restore(TraceRemnant remnant) {
        synchronized (logs) {
            droppedLogs += remnant.droppedLogs();
        }
        synchronized (spansLock) {
            droppedSpans += remnant.droppedSpans();
            parentlessSpanStored |= remnant.parentlessSpanStored();
            droppedInputFailed |= remnant.failed();
            fullWindow = fullWindow.including(new Window(remnant.start(), remnant.end()));
            asyncMembership.restoreEvicted(new Window(remnant.evictedSyncStart(), remnant.evictedSyncEnd()));
            asyncMembership.restoreMembers(remnant.asyncSpanIds());
        }
    }

    public void setRequest(RequestCompletedEvent request) {
        this.request = request;
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
     * must run under {@code spansLock}, as it consults {@link DuplicateFolds}.
     */
    private SpanData withResolvedParent(SpanData span) {
        if (span.parentId() == null) {
            return span;
        }
        String resolvedParentId = folds.resolve(span.parentId());
        return Objects.equals(resolvedParentId, span.parentId()) ? span : span.withParentId(resolvedParentId);
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

    /** The failure is read first, so a trace that did not fail scans no span. */
    public boolean completeAndFailed() {
        synchronized (spansLock) {
            return failed() && isComplete(rootOf(spansById.values()));
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
     * must run under {@code spansLock}, as it consults {@link DuplicateFolds}.
     */
    private @Nullable SpanData rootOf(Collection<SpanData> spans) {
        SpanData parentless = null;
        SpanData continuingCaller = null;
        for (SpanData span : spans) {
            if (span.parentId() == null) {
                parentless = earlier(parentless, span);
            } else if (span.remoteParent() && !spansById.containsKey(folds.resolve(span.parentId()))) {
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

    /** Whether the store has to look in Slow to take the trace out of it. */
    boolean listedSlow() {
        synchronized (spansLock) {
            return listedSlow;
        }
    }

    void listedSlow(boolean listed) {
        synchronized (spansLock) {
            listedSlow = listed;
        }
    }

    /** The Errors rule; must run under {@code spansLock}. */
    private boolean failed() {
        return hasErrorSpan || hasServerErrorSpan || hasErrorLog || droppedInputFailed;
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
            if (asyncMembership.contains(stored.spanId())
                    && !asyncMembership.contains(folds.resolve(stored.parentId()))) {
                entries.add(stored);
            }
        }
        return entries;
    }

    public RequestCompletedEvent request() {
        return request;
    }
}
