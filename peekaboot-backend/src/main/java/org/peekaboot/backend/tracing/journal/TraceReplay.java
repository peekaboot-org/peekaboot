package org.peekaboot.backend.tracing.journal;

import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays the snapshot and then the journal into the store through its own methods, so buckets,
 * eviction and caps come out as they did live, in the order the journal wrote the inputs. Two traces
 * that arrived at the same moment may come back swapped, and a full bucket may keep the other one of the
 * two. Every summary and issue is computed by the code running now. A snapshot ends with a line for each
 * bucket, which lists its traces exactly; until then All evicts nothing. Once the files are read the live
 * run begins, which ends the last restored run.
 *
 * <p>The files are a cache: whatever is wrong with them costs only what they held, so a restore says
 * so once, at INFO. Files of another schema version, or with a damaged header, are deleted. A last line
 * cut short by a crash is expected and says nothing.
 */
final class TraceReplay {

    private static final Logger log = LoggerFactory.getLogger(TraceReplay.class);

    private TraceReplay() {}

    static void restore(JournalFiles files, TraceStore store) {
        try {
            JournalFiles.Contents contents = replay(files, store, (text, line) -> {});
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

    /**
     * Replays the files into {@code store}, handing each line to {@code alongside} just before the store
     * takes it. Logs nothing and begins no live run; a fold replays this way into a store of its own.
     */
    static JournalFiles.Contents replay(JournalFiles files, TraceStore store, JournalFiles.LineVisitor alongside)
            throws IOException {
        Runs runs = new Runs(store);
        // fewer lines evict in another order; the snapshot's bucket lines, not All's cap, decide its traces
        store.holdAll(true);
        try {
            return files.read(
                    (text, line) -> {
                        alongside.visit(text, line);
                        apply(line, store, runs);
                    },
                    () -> store.holdAll(false));
        } finally {
            store.holdAll(false);
        }
    }

    private static void apply(JournalLine line, TraceStore store, Runs runs) {
        switch (line) {
            case JournalLine.RunLine run -> runs.begin(run.startedAt());
            case JournalLine.SpanLine span -> store.addSpan(span.span());
            case JournalLine.LogLine logged -> store.addLog(logged.toEvent());
            case JournalLine.RequestLine request -> store.setRequest(request.request());
            case JournalLine.DiscardLine discard -> store.discard(discard.traceId());
            case JournalLine.RemnantLine remnant -> store.restoreRemnant(remnant.traceId(), remnant.remnant());
            case JournalLine.BucketLine listing -> store.restoreBucket(listing.bucket(), listing.traceIds());
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
