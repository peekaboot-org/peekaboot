package org.peekaboot.backend.tracing.journal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceRemnant;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Folds the journal into the snapshot. The files are replayed into an empty store with the live
 * store's caps, and the lines of what that store keeps are copied as written, in arrival order. A
 * trace whose caps dropped anything, or whose bundle began with a line it does not keep, gets a
 * remnant line where its bundle began, so a restore shows the trace as the store held it and the
 * snapshot stays within the caps. A run line is copied where a copied line of its run follows.
 *
 * <p>The snapshot ends with a line for each bucket listing its traces in the replayed store's order,
 * Errors and Slow first, then All: a restore lists exactly those, so every bucket comes back exactly as
 * this fold saw it. The replay of fewer lines evicts in another order, which is why the lines are
 * written and not left to the caps.
 *
 * <p>The new snapshot names the journal it absorbed, then the next journal starts under a new id; a
 * crash in between leaves a journal the next restore skips. The replay holds a second store's worth of
 * traces while the fold runs.
 */
final class Checkpoint implements JournalWriter.Folding {

    private final JournalFiles files;
    private final Supplier<TraceStore> emptyStore;

    /** {@code emptyStore} builds a store with the live store's caps. */
    Checkpoint(JournalFiles files, Supplier<TraceStore> emptyStore) {
        this.files = files;
        this.emptyStore = emptyStore;
    }

    @Override
    public OpenJournal fold() throws IOException {
        TraceStore kept = emptyStore.get();
        FoldTally tally = new FoldTally();
        TraceReplay.replay(files, kept, (text, line) -> tally.count(line, kept));
        String absorbed = files.journalId();
        files.replaceSnapshot(stream -> write(stream, kept, tally, absorbed));
        return files.startJournal();
    }

    private void write(OutputStream stream, TraceStore kept, FoldTally tally, @Nullable String absorbed)
            throws IOException {
        Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        writeLine(out, JournalCodec.encode(new JournalLine.SnapshotHeader(JournalCodec.VERSION, absorbed)));
        files.read(new Folder(out, kept, tally)::visit);
        // the restore finds a trace only Errors or Slow keeps through All, which holds every trace until its line
        for (TraceBucket bucket : List.of(TraceBucket.ERRORS, TraceBucket.SLOW, TraceBucket.ALL)) {
            writeLine(out, JournalCodec.encode(new JournalLine.BucketLine(bucket, oldestFirst(kept, bucket))));
        }
        out.flush();
    }

    private static List<String> oldestFirst(TraceStore kept, TraceBucket bucket) {
        return kept.getTraces(bucket, Integer.MAX_VALUE).reversed().stream()
                .map(listed -> listed.bundle().traceId())
                .toList();
    }

    private static void writeLine(Writer out, String text) throws IOException {
        out.write(text);
        out.write('\n');
    }

    /** The second pass: copies what the replayed store keeps, with a remnant line where a trace's bundle began. */
    private static final class Folder {

        private final Writer out;
        private final TraceStore kept;
        private final FoldTally tally;
        private final long untracedToSkip;
        private long untracedSeen;

        @Nullable
        private String pendingRunLine;

        Folder(Writer out, TraceStore kept, FoldTally tally) {
            this.out = out;
            this.kept = kept;
            this.tally = tally;
            this.untracedToSkip = tally.untracedLines() - kept.untracedLogs().size();
        }

        void visit(String text, JournalLine line) throws IOException {
            if (line instanceof JournalLine.RunLine) {
                pendingRunLine = text;
                return;
            }
            String traceId = line.traceId();
            if (traceId == null) {
                if (line instanceof JournalLine.LogLine && keepsNextUntraced()) {
                    copy(text);
                }
                return;
            }
            TraceDataBundle bundle = kept.getTrace(traceId).orElse(null);
            FoldTally.Trace counts = tally.of(traceId);
            if (bundle != null && counts.advance()) {
                copyKept(text, line, bundle, counts);
            }
        }

        /** A line of a bundle the store keeps, with a remnant before the line that began it. */
        private void copyKept(String text, JournalLine line, TraceDataBundle bundle, FoldTally.Trace counts)
                throws IOException {
            boolean beginsBundle = counts.atFirstLiveLine();
            if (beginsBundle) {
                counts.keepNewestLogs(bundle.capturedLogs().logs().size());
            }
            boolean keepsLine = keeps(line, bundle, counts);
            if (beginsBundle) {
                TraceRemnant remnant = bundle.remnant();
                // a remnant where the line that began the bundle is not kept pins the run the bundle began in
                if (remnant.droppedAnything() || !keepsLine) {
                    copy(JournalCodec.encode(new JournalLine.RemnantLine(bundle.traceId(), remnant)));
                }
            }
            if (keepsLine) {
                copy(text);
            }
        }

        private static boolean keeps(JournalLine line, TraceDataBundle bundle, FoldTally.Trace counts) {
            if (line instanceof JournalLine.SpanLine span) {
                return bundle.keepsSpan(span.span().spanId());
            }
            if (line instanceof JournalLine.LogLine) {
                return counts.keepsNextLog();
            }
            // otherwise a line an earlier fold wrote, which gives way to the ones written here
            return line instanceof JournalLine.RequestLine && counts.keepsNextRequest();
        }

        /** The ring keeps the newest lines written outside any trace. */
        private boolean keepsNextUntraced() {
            untracedSeen++;
            return untracedSeen > untracedToSkip;
        }

        private void copy(String text) throws IOException {
            if (pendingRunLine != null) {
                writeLine(out, pendingRunLine);
                pendingRunLine = null;
            }
            writeLine(out, text);
        }
    }
}
