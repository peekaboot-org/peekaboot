package org.peekaboot.backend.tracing.digest;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.domain.trace.TraceSummary;

/**
 * Every failed trace the application completed, folded into one {@link DigestedError} per failure. It
 * keeps counting after the traces themselves are gone: one noisy failure can fill the Errors bucket,
 * and the digest still holds every other failure beside it. At most {@link #MAX_ENTRIES}; past that the
 * entry seen least recently goes. Thread-safe: every method holds the digest's lock.
 */
public final class ErrorDigest {

    static final int MAX_ENTRIES = 500;
    static final int MAX_RUNS = 10;

    /**
     * Trace ids claimed lately, held as references only; enough for the inputs that race to fail one
     * trace and for a request that calls its own application to reach its outer root.
     */
    static final int REMEMBERED_TRACES = 1_000;

    private static final Comparator<DigestedError> LATEST_FIRST =
            Comparator.comparing(DigestedError::lastSeen).reversed();

    private final Clock clock;
    private final Map<DigestedError.Key, DigestedError> entries = new LinkedHashMap<>();
    private final Set<String> claimed = new LinkedHashSet<>();
    private long changes;

    public ErrorDigest() {
        this(Clock.systemUTC());
    }

    /** {@code clock} dates a trace whose summary names no start; tests fix it. */
    ErrorDigest(Clock clock) {
        this.clock = clock;
    }

    /**
     * True the first time it is asked for {@code traceId}, false after. Of two inputs that both saw a
     * trace become complete and failed, only one records it. A request that calls its own application
     * becomes complete twice, once with the inner request and again with the outer root, and only the
     * first counts. Remembers the latest {@link #REMEMBERED_TRACES} ids; a trace forgotten before its
     * outer root arrives counts again.
     */
    public synchronized boolean claim(String traceId) {
        if (!claimed.add(traceId)) {
            return false;
        }
        if (claimed.size() > REMEMBERED_TRACES) {
            Iterator<String> oldest = claimed.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Counts {@code failed}, a complete trace whose outcome is ERROR, under its cause, its route and its frame. */
    public synchronized void record(TraceSummary failed) {
        TraceSummary.Cause cause = failed.cause();
        TraceSummary.Request request = failed.request();
        Instant start = failed.startTime();
        String route = request == null ? null : request.route();
        DigestedError.Key key = cause == null
                ? DigestedError.Key.of(null, route, null)
                : DigestedError.Key.of(cause.type(), route, cause.applicationFrame());
        Instant seen = start != null ? start : clock.instant();
        String message = cause == null ? null : cause.message();
        DigestedError known = entries.get(key);
        entries.put(
                key,
                known == null
                        ? DigestedError.first(key, seen, message, failed.traceId(), failed.run())
                        : known.seenAgain(seen, message, failed.traceId(), failed.run()));
        evictPastCap();
        changes++;
    }

    /** Every entry, the one seen latest first. */
    public synchronized List<DigestedError> entries() {
        return entries.values().stream().sorted(LATEST_FIRST).toList();
    }

    /**
     * Puts back what an earlier run recorded, before this run records anything. An entry this run
     * already holds stays as it is. Leaves {@link #changes} as it was: what was restored is what the
     * file already holds.
     */
    public synchronized void restore(List<DigestedError> restored) {
        restored.forEach(entry -> entries.putIfAbsent(entry.key(), entry));
        evictPastCap();
    }

    /** Rises with every recorded trace, so a writer can tell whether the digest changed since it last wrote. */
    public synchronized long changes() {
        return changes;
    }

    private void evictPastCap() {
        while (entries.size() > MAX_ENTRIES) {
            DigestedError seenLeastRecently = entries.values().stream()
                    .min(Comparator.comparing(DigestedError::lastSeen))
                    .orElseThrow();
            entries.remove(seenLeastRecently.key());
        }
    }
}
