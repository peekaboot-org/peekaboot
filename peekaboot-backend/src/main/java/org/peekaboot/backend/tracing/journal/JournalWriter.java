package org.peekaboot.backend.tracing.journal;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The trace journal's one writer thread: appends every input it is handed as a line, flushes on a
 * cadence, and folds the journal into the snapshot at its start, past the size limit and at its stop.
 * Inputs wait in a queue of at most {@link #CAPACITY}; past that an input is dropped, so the exporter
 * and the logging threads never wait, and the drops are reported at most once a minute. The run's start
 * is never dropped, since without its run line the run's inputs would be held back for good.
 *
 * <p>Every flush, and the stop, also runs {@code onFlush}, the error digest's write, on this thread.
 *
 * <p>A run line carries the start the run reports once it is ready, so inputs that arrive before
 * that are held back until the run line is written: every line after a run line belongs to its run.
 * A write or fold that fails, checked or unchecked, warns once and ends writing for the run. The
 * writer keeps draining the queue so nothing else is logged, and the store, which the inputs
 * reached first, keeps going in memory.
 */
final class JournalWriter {

    private static final Logger log = LoggerFactory.getLogger(JournalWriter.class);

    /** Bounds the queue and the inputs held back for a run line; references to events the store holds anyway. */
    static final int CAPACITY = 10_000;

    /** Wakes the writer for a stop. A trace id no tracer mints (they are hex), so it never equals a real input. */
    private static final TraceInput STOP = new TraceInput.TraceDiscarded("peekaboot-journal-stop");

    /** Folds what the files hold into the snapshot and starts the next journal. */
    @FunctionalInterface
    interface Folding {
        OpenJournal fold() throws IOException;
    }

    /** Unbounded so the stop pill always lands; offer() keeps the inputs to CAPACITY. */
    private final BlockingQueue<TraceInput> queue = new LinkedBlockingQueue<>();

    private final DropReport drops = new DropReport();
    private final JournalFiles files;
    private final Folding folding;
    private final Duration flushInterval;
    private final long maxJournalChars;
    private final Clock clock;
    private final Runnable onFlush;
    private volatile boolean stopping;

    /**
     * {@code onFlush} runs on the writer thread after each flush and once more at the stop, before the last
     * fold, whether or not the journal still writes.
     */
    JournalWriter(
            JournalFiles files,
            Folding folding,
            Duration flushInterval,
            long maxJournalChars,
            Clock clock,
            Runnable onFlush) {
        this.files = files;
        this.folding = folding;
        this.flushInterval = flushInterval;
        this.maxJournalChars = maxJournalChars;
        this.clock = clock;
        this.onFlush = onFlush;
    }

    /**
     * Hands {@code input} to the writer; with {@link #CAPACITY} inputs already queued it is dropped
     * and counted, unless it is the run's start. Once {@link #stop} was called, an input may be lost
     * without being written or counted.
     */
    void offer(TraceInput input) {
        if (stopping) {
            return;
        }
        if (queue.size() < CAPACITY || input instanceof TraceInput.RunStarted) {
            queue.add(input);
        } else {
            drops.count();
        }
    }

    /** Ends the writer once it has written what is queued and folded the files once more. */
    void stop() {
        stopping = true;
        queue.add(STOP);
    }

    /** The writer thread's body. Returns once {@link #stop} was called and the queue is empty, or on an interrupt. */
    void run() {
        Appending appending = new Appending(files, folding, drops);
        appending.start();
        boolean interrupted = false;
        Instant nextFlush = clock.instant().plus(flushInterval);
        while (!stopping || !queue.isEmpty()) {
            try {
                TraceInput input = queue.poll(millisUntil(nextFlush), TimeUnit.MILLISECONDS);
                if (input != null && !STOP.equals(input)) {
                    appending.write(input);
                }
            } catch (InterruptedException e) {
                // an interrupt ends the writer as a stop does; the flag goes back once the last fold is done
                interrupted = true;
                stopping = true;
            }
            Instant now = clock.instant();
            if (!now.isBefore(nextFlush)) {
                appending.flush(now);
                onFlush.run();
                nextFlush = now.plus(flushInterval);
            } else if (nextFlush.isAfter(now.plus(flushInterval))) {
                // the clock stepped back; waiting for the old instant would keep the lines in memory that long
                nextFlush = now.plus(flushInterval);
            }
            appending.foldPast(maxJournalChars);
        }
        onFlush.run();
        appending.finish();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long millisUntil(Instant at) {
        return Math.max(0, Duration.between(clock.instant(), at).toMillis());
    }

    /** The writer thread's own state. run() creates it on that thread, and nothing else reaches it. */
    private static final class Appending {

        private final JournalFiles files;
        private final Folding folding;
        private final DropReport drops;
        private final List<TraceInput> heldBack = new ArrayList<>();

        @Nullable
        private OpenJournal journal;

        @Nullable
        private Instant runStartedAt;

        private boolean runLineWritten;
        private boolean ended;

        Appending(JournalFiles files, Folding folding, DropReport drops) {
            this.files = files;
            this.folding = folding;
            this.drops = drops;
        }

        /** The fold at the writer's start, which opens the run's first journal. */
        void start() {
            journal = open();
        }

        void write(TraceInput input) {
            if (input instanceof TraceInput.RunStarted started) {
                runStartedAt = started.startedAt();
                writeRunLine();
            } else if (runLineWritten) {
                append(input);
            } else if (heldBack.size() < CAPACITY) {
                heldBack.add(input);
            } else {
                drops.count();
            }
        }

        /**
         * The cadence step: the lines reach the file, and the drops since the last report are logged.
         * Once writing has ended, the failure's WARN already says nothing more survives a restart.
         */
        void flush(Instant now) {
            if (journal == null) {
                return;
            }
            try {
                journal.flush();
            } catch (IOException e) {
                failed(e);
                return;
            }
            long dropped = drops.due(now);
            if (dropped > 0) {
                log.warn(
                        "Peekaboot traces: the journal could not keep up and dropped {} inputs; they will not survive a restart",
                        dropped);
            }
        }

        /** Past the limit the journal is folded, and the next one starts with the run line of the run in progress. */
        void foldPast(long maxJournalChars) {
            if (journal == null || journal.chars() <= maxJournalChars) {
                return;
            }
            close();
            journal = open();
            if (runLineWritten) {
                appendRunLine();
            }
        }

        /** At a stop: a run that never reported ready still gets its run line and what it held back, then the last fold. */
        void finish() {
            if (!runLineWritten) {
                writeRunLine();
            }
            close();
            journal = open();
            close();
        }

        private void writeRunLine() {
            appendRunLine();
            runLineWritten = true;
            heldBack.forEach(this::append);
            heldBack.clear();
        }

        private void appendRunLine() {
            append(() -> new JournalLine.RunLine(runStartedAt));
        }

        private void append(TraceInput input) {
            append(() -> JournalCodec.lineOf(input));
        }

        /** Builds the line inside the try, so a line that refuses its input ends writing as a failed write does. */
        private void append(Supplier<JournalLine> line) {
            if (journal == null) {
                return;
            }
            try {
                journal.write(line.get());
            } catch (IOException | RuntimeException e) {
                failed(e);
            }
        }

        /** The next journal, or null once writing has ended: a fold after a failed close would resume writing. */
        @Nullable
        private OpenJournal open() {
            if (ended) {
                return null;
            }
            try {
                return folding.fold();
            } catch (IOException | RuntimeException e) {
                failed(e);
                return null;
            }
        }

        private void close() {
            if (journal == null) {
                return;
            }
            // forgotten before it is closed, so a failure to close finds nothing left to close again
            try (OpenJournal _ = journal) {
                journal = null;
            } catch (IOException e) {
                failed(e);
            }
        }

        /** Ends writing for the run with one WARN. The broken journal is closed to release its file; a failure to close is suppressed. */
        private void failed(Exception e) {
            ended = true;
            if (journal != null) {
                try (OpenJournal _ = journal) {
                    journal = null;
                } catch (IOException closing) {
                    e.addSuppressed(closing);
                }
            }
            log.warn(
                    "Peekaboot traces: cannot write to {} ({}); traces from here on will not survive a restart",
                    files.directory(),
                    e.toString());
        }
    }
}
