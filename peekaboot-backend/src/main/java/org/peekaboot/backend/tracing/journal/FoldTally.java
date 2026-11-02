package org.peekaboot.backend.tracing.journal;

import java.util.HashMap;
import java.util.Map;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * The fold's first pass: for each trace, where the bundle the replayed store keeps for it began and how
 * many logs and requests arrived since, and how many lines were written outside any trace. A discard,
 * or an eviction from every bucket, ends a bundle and the trace's next line begins another, so only
 * what arrived from that line on is the trace a restore shows.
 */
final class FoldTally {

    private final Map<String, Trace> traces = new HashMap<>();
    private long untracedLines;

    /** Counts {@code line} before {@code store} takes it, so a trace the store holds nothing for begins a bundle with it. */
    void count(JournalLine line, TraceStore store) {
        String traceId = line.traceId();
        if (traceId != null) {
            boolean beginsBundle = !(line instanceof JournalLine.DiscardLine)
                    && store.getTrace(traceId).isEmpty();
            traces.computeIfAbsent(traceId, id -> new Trace()).count(line, beginsBundle);
        } else if (line instanceof JournalLine.LogLine) {
            untracedLines++;
        }
    }

    long untracedLines() {
        return untracedLines;
    }

    /** Every traced line the second pass visits was counted here, since both passes read the same files. */
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
