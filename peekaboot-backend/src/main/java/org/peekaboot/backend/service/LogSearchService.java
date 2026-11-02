package org.peekaboot.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.log.LogSearchParameters;
import org.peekaboot.backend.domain.log.LogSearchResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.mapper.trace.TraceLogMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;

/** Searches the log lines the store holds, inside traces and outside them, newest first. */
public class LogSearchService {

    private static final LogSearchResponse EMPTY = new LogSearchResponse(List.of(), null);

    /** The store's own count, so traced and untraced lines interleave in the order they arrived. */
    private static final Comparator<CandidateLog> NEWEST_FIRST = Comparator.comparingLong(
                    (CandidateLog candidate) -> candidate.log().sequence())
            .reversed();

    @Nullable
    private final TraceStore traceStore;

    private final TraceLogMapper traceLogMapper;

    public LogSearchService(
            @Nullable TraceStore traceStore, MaskingEngine maskingEngine, StackTraceRules stackTraceRules) {
        this.traceStore = traceStore;
        this.traceLogMapper = new TraceLogMapper(maskingEngine, stackTraceRules);
    }

    /**
     * One page of the lines that match every filter, newest first. Each parameter is read and checked
     * even while tracing is off. Lines are mapped, and so masked, one at a time and only until the page
     * is full, because {@code q} reads the masked text. {@code next} names the page's last line while
     * an older match exists. A line stored after the search read its trace can still appear on a later
     * page, below the cursor, since the store numbers lines from several threads at once. A line still
     * being stored while a page is read can also carry a number between two lines that page served;
     * no later page shows it, so it waits for the next search from the newest line, like a line that
     * arrives between pages.
     */
    public LogSearchResponse search(LogSearchParameters parameters) {
        LogQuery query = LogQuery.of(parameters);
        if (traceStore == null) {
            return EMPTY;
        }
        List<TraceLog> page = new ArrayList<>();
        long lastSequence = 0;
        for (CandidateLog candidate : candidates(traceStore, query)) {
            TraceLog mapped = traceLogMapper.map(candidate.spanOf(), candidate.log());
            if (!query.matchesText(mapped)) {
                continue;
            }
            if (page.size() == query.limit()) {
                return new LogSearchResponse(page, Cursors.ofLog(lastSequence));
            }
            page.add(mapped);
            lastSequence = candidate.log().sequence();
        }
        return new LogSearchResponse(page, null);
    }

    /**
     * Every line that passes the filters a stored line can answer, newest first. Each bundle's logs are
     * one read under its log lock and the untraced ring one read under its own; a trace evicted after
     * the store named it is still searched, as it stood. The store names the bundles by reading each
     * bucket under its own lock, and the mapping resolves each span id under the bundle's span lock.
     */
    private static List<CandidateLog> candidates(TraceStore traceStore, LogQuery query) {
        List<CandidateLog> candidates = new ArrayList<>();
        if (query.traceId() != null) {
            traceStore.getTrace(query.traceId()).ifPresent(bundle -> addTraced(bundle, query, candidates));
        } else {
            traceStore.storedTraces().forEach(bundle -> addTraced(bundle, query, candidates));
            for (LogCapturedEvent log : traceStore.untracedLogs()) {
                if (query.admits(log)) {
                    candidates.add(new CandidateLog(log, UnaryOperator.identity()));
                }
            }
        }
        candidates.sort(NEWEST_FIRST);
        return candidates;
    }

    /** One span resolver per bundle, so its lines agree on the span each names. */
    private static void addTraced(TraceDataBundle bundle, LogQuery query, List<CandidateLog> candidates) {
        UnaryOperator<String> spanOf = TraceLogMapper.resolvingOnce(bundle);
        for (LogCapturedEvent log : bundle.capturedLogs().logs()) {
            if (query.admits(log)) {
                candidates.add(new CandidateLog(log, spanOf));
            }
        }
    }

    private record CandidateLog(LogCapturedEvent log, UnaryOperator<String> spanOf) {}
}
