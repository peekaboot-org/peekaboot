package org.peekaboot.backend.tracing.journal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * The fold's first pass: for each trace, where the bundle the replayed store keeps for it began and how
 * many logs and requests arrived since, and how many lines were written outside any trace. A discard,
 * or an eviction from every bucket, ends a bundle and the trace's next line begins another, so only
 * what arrived from that line on is the trace a restore shows. A line the store refused is in none of
 * the counts, so the second pass leaves it out and loses no other line for it.
 */
final class FoldTally {

    private final Map<String, Trace> traces = new HashMap<>();
    private final Set<String> refused = new HashSet<>();
    private long untracedLines;

    /** Asked before {@code store} takes {@code line}: a trace the store holds nothing for begins a bundle with it. */
    boolean beginsBundle(JournalLine line, TraceStore store) {
        String traceId = line.traceId();
        return traceId != null
                && !(line instanceof JournalLine.DiscardLine)
                && store.getTrace(traceId).isEmpty();
    }

    /** Told once the store took {@code line}, with what {@link #beginsBundle} answered for it. */
    void count(JournalLine line, boolean beginsBundle) {
        String traceId = line.traceId();
        if (traceId != null) {
            traces.computeIfAbsent(traceId, id -> new Trace()).count(line, beginsBundle);
        } else if (line instanceof JournalLine.LogLine) {
            untracedLines++;
        }
    }

    /** Told in place of {@link #count} where the store threw on the line written as {@code text}. */
    void refuse(String text) {
        refused.add(text);
    }

    boolean refused(String text) {
        return refused.contains(text);
    }

    long untracedLines() {
        return untracedLines;
    }

    /**
     * Every traced line the second pass looks up was counted here: both passes read the same files, and
     * the second skips a refused line before it looks.
     */
    Trace of(String traceId) {
        return traces.get(traceId);
    }

    /** One trace's counts from the first pass, and its place in the second. */
    static final class Trace {

        private int lines;
        private int bundleBeganAt;
        private int logs;
        private int requests;
        private int seen;
        private int logsToSkip;
        private int logsSeen;
        private int requestsSeen;

        private void count(JournalLine line, boolean beginsBundle) {
            lines++;
            if (beginsBundle) {
                bundleBeganAt = lines;
                logs = 0;
                requests = 0;
            }
            if (line instanceof JournalLine.LogLine) {
                logs++;
            } else if (line instanceof JournalLine.RequestLine) {
                requests++;
            }
        }

        /** Moves to the trace's next line in the second pass; false while that line came before the kept bundle began. */
        boolean advance() {
            seen++;
            return seen >= bundleBeganAt;
        }

        boolean atFirstLiveLine() {
            return seen == bundleBeganAt;
        }

        /** The bundle keeps the newest {@code keptLogs} of the logs since it began. */
        void keepNewestLogs(int keptLogs) {
            logsToSkip = logs - keptLogs;
        }

        boolean keepsNextLog() {
            logsSeen++;
            return logsSeen > logsToSkip;
        }

        /** A request replaces the one before it, so only the last is kept. */
        boolean keepsNextRequest() {
            requestsSeen++;
            return requestsSeen == requests;
        }
    }
}
