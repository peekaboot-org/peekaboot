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
 * so once, at INFO. Files of another schema version, or with a damaged header, are deleted. A line the
 * store throws on is skipped and counted like one that cannot be read, and the replay goes on with the
 * next. A last line cut short by a crash is expected and says nothing.
 */
final class TraceReplay {

    private static final Logger log = LoggerFactory.getLogger(TraceReplay.class);

    private TraceReplay() {}

    static void restore(JournalFiles files, TraceStore store) {
        try {
            Replayed replayed = replay(files, store, null);
            JournalFiles.Contents contents = replayed.contents();
            RuntimeException firstRefusal = replayed.firstRefusal();
            if (contents.foreign()) {
                log.info(
                        "Peekaboot traces: the files in {} come from another Peekaboot version or start with a"
                                + " damaged header; starting without them",
                        files.directory());
                deleteBoth(files);
            } else if (firstRefusal != null) {
                log.info(
                        "Peekaboot traces: skipped {} unreadable lines and {} the store refused ({}) in {}; restored"
                                + " the rest",
                        contents.unreadableLines(),
                        replayed.refusedLines(),
                        firstRefusal.toString(),
                        files.directory());
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
     * Replays the files into {@code store}, skipping each line the store throws on. Logs nothing and begins
     * no live run; a fold replays this way into a store of its own and passes its {@code tally}, which
     * counts the lines the store took and notes the ones it refused.
     */
    static Replayed replay(JournalFiles files, TraceStore store, @Nullable FoldTally tally) throws IOException {
        Lines lines = new Lines(store, tally);
        // fewer lines evict in another order; the snapshot's bucket lines, not All's cap, decide its traces
        store.holdAll(true);
        try {
            JournalFiles.Contents contents = files.read(lines::replay, () -> store.holdAll(false));
            return new Replayed(contents, lines.refused, lines.firstRefusal);
        } finally {
            store.holdAll(false);
        }
    }

    /** What {@link #replay} found. {@code refusedLines}: the lines the store threw on; {@code firstRefusal}: the first throw. */
    record Replayed(
            JournalFiles.Contents contents,
            int refusedLines,
            @Nullable RuntimeException firstRefusal) {}

    /** A file whose header is foreign fails every start; deleting it keeps that from repeating. */
    private static void deleteBoth(JournalFiles files) {
        try {
            files.deleteBoth();
        } catch (IOException e) {
            log.debug("Could not delete the unusable trace journal in {}", files.directory(), e);
        }
    }

    /** Replays one line at a time, so a line the store refuses costs itself alone. */
    private static final class Lines {

        private final TraceStore store;
        private final Runs runs;

        @Nullable
        private final FoldTally tally;

        private int refused;

        @Nullable
        private RuntimeException firstRefusal;

        Lines(TraceStore store, @Nullable FoldTally tally) {
            this.store = store;
            this.runs = new Runs(store);
            this.tally = tally;
        }

        void replay(String text, JournalLine line) {
            boolean beginsBundle = tally != null && tally.beginsBundle(line, store);
            try {
                apply(line);
            } catch (RuntimeException e) {
                refuse(text, e);
                return;
            }
            if (tally != null) {
                tally.count(line, beginsBundle);
            }
        }

        private void apply(JournalLine line) {
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

        private void refuse(String text, RuntimeException refusal) {
            refused++;
            if (firstRefusal == null) {
                firstRefusal = refusal;
            }
            if (tally != null) {
                tally.refuse(text);
            }
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
