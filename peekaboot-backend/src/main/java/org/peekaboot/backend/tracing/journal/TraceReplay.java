package org.peekaboot.backend.tracing.journal;

import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays the snapshot and then the journal into the store through its own methods, so buckets,
 * eviction and caps come out as they did live and every summary and issue is computed by the code
 * running now. Once the files are read the live run begins, which ends the last restored run.
 *
 * <p>The files are a cache: whatever is wrong with them costs only what they held, so a restore says
 * so once, at INFO. Files of another schema version, or with a damaged header, are deleted. A last line
 * cut short by a crash is expected and says nothing.
 */
final class TraceReplay {

    private static final Logger log = LoggerFactory.getLogger(TraceReplay.class);

    private TraceReplay() {}

    static void restore(JournalFiles files, TraceStore store) {
        Runs runs = new Runs(store);
        try {
            JournalFiles.Contents contents = files.read((text, line) -> replay(line, store, runs));
            if (contents.foreign()) {
                log.info(
                        "Peekaboot traces: the files in {} come from another Peekaboot version or start with a"
                                + " damaged header; starting without them",
                        files.directory());
                deleteBoth(files);
            } else if (contents.unreadableLines() > 0) {
                log.info(
                        "Peekaboot traces: skipped {} unreadable lines in {}; restored the rest",
                        contents.unreadableLines(),
                        files.directory());
            }
        } catch (IOException | RuntimeException e) {
            log.info(
                    "Peekaboot traces: cannot read {} ({}); restored what was read before it",
                    files.directory(),
                    e.toString());
        } finally {
            store.beginRun(null);
        }
    }

    private static void replay(JournalLine line, TraceStore store, Runs runs) {
        switch (line) {
            case JournalLine.RunLine run -> runs.begin(run.startedAt());
            case JournalLine.SpanLine span -> store.addSpan(span.span());
            case JournalLine.LogLine logged -> store.addLog(logged.toEvent());
            case JournalLine.RequestLine request -> store.setRequest(request.request());
            case JournalLine.DiscardLine discard -> store.discard(discard.traceId());
            case JournalLine.SnapshotHeader _, JournalLine.JournalHeader _ -> {
                // a header names nothing to replay; JournalFiles checks the one each file starts with
            }
        }
    }

    /** A file whose header is foreign fails every start; deleting it keeps that from repeating. */
    private static void deleteBoth(JournalFiles files) {
        try {
            files.deleteBoth();
        } catch (IOException e) {
            log.debug("Could not delete the unusable trace journal in {}", files.directory(), e);
        }
    }

    /** A run line naming the run in progress continues it, since a fold repeats the run line atop the next journal. */
    private static final class Runs {

        private final TraceStore store;
        private boolean begun;

        @Nullable
        private Instant current;

        Runs(TraceStore store) {
            this.store = store;
        }

        void begin(@Nullable Instant startedAt) {
            if (begun && startedAt != null && startedAt.equals(current)) {
                return;
            }
            store.beginRun(startedAt);
            begun = true;
            current = startedAt;
        }
    }
}
