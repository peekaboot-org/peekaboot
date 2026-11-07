package org.peekaboot.backend.tracing.journal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.digest.ErrorDigestFile;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the store's traces across restarts. Every input reaches the store first and is then handed
 * to one writer thread, which appends it to {@code traces.journal.jsonl}. That thread's first work
 * is the restore: it replays the files into the store while a {@link RestoreGate} holds live inputs
 * back, opens the gate, and folds the files into a new snapshot. Closing writes what is queued and
 * folds once more. The journal holds the inputs as the store takes them. Headers, the query string and
 * parameters arrive masked from capture; span attributes, SQL with its bind parameters, log messages and
 * stack traces are written raw and masked when read.
 */
public final class TraceJournal implements TraceInputs, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TraceJournal.class);

    /** How long a close waits for the last fold; past it the JVM may exit with the journal as last flushed. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final TraceStore store;
    private final TraceInputs live;
    private final ErrorDigestFile digestFile;
    private final RestoreGate gate = new RestoreGate(JournalWriter.CAPACITY);
    private final JournalWriter writer;
    private final Thread thread;

    private TraceJournal(
            TraceStore store, TraceInputs live, ErrorDigestFile digestFile, JournalFiles files, JournalWriter writer) {
        this.store = store;
        this.live = live;
        this.digestFile = digestFile;
        this.writer = writer;
        this.thread = Thread.ofVirtual().name("peekaboot-trace-journal").unstarted(() -> run(files));
    }

    /**
     * Starts the writer thread and returns at once, so no startup waits for the files. The restore
     * replays into {@code store}; every live input reaches {@code live}, which applies it to that store
     * and hands it to whatever else takes live inputs. The writer keeps {@code digestFile} beside the
     * journal: restored before the gate opens, written on every flush.
     */
    public static TraceJournal start(
            TraceStore store,
            TraceInputs live,
            ErrorDigestFile digestFile,
            Path directory,
            PeekabootProperties.Storage.Traces settings,
            PeekabootTracingProperties tracing) {
        return start(
                store,
                live,
                digestFile,
                directory,
                settings.getFlushInterval(),
                settings.getMaxJournalSize().toBytes(),
                () -> new InMemoryTraceStore(tracing));
    }

    /** {@code emptyStore} builds the store a fold replays into, with the live store's caps; tests shorten the cadence. */
    static TraceJournal start(
            TraceStore store,
            TraceInputs live,
            ErrorDigestFile digestFile,
            Path directory,
            Duration flushInterval,
            long maxJournalChars,
            Supplier<TraceStore> emptyStore) {
        JournalFiles files = new JournalFiles(directory);
        JournalWriter writer = new JournalWriter(
                files,
                new Checkpoint(files, emptyStore),
                flushInterval,
                maxJournalChars,
                Clock.systemUTC(),
                digestFile::flush);
        TraceJournal journal = new TraceJournal(store, live, digestFile, files, writer);
        journal.thread.start();
        return journal;
    }

    /** The gate opens however the restore ended, or every live input would wait for good. */
    private void run(JournalFiles files) {
        try {
            TraceReplay.restore(files, store);
            digestFile.restore();
        } finally {
            gate.open(live);
        }
        if (gate.dropped() > 0) {
            log.warn(
                    "Peekaboot traces: dropped {} inputs that arrived while the stored traces were being restored",
                    gate.dropped());
        }
        RuntimeException failure = gate.firstFailure();
        if (failure != null) {
            log.warn(
                    "Peekaboot traces: could not apply {} inputs that arrived while the stored traces were being"
                            + " restored ({})",
                    gate.failed(),
                    failure.toString());
        }
        writer.run();
    }

    /** An input the gate drops reaches neither the store nor the writer; the restore's end reports the count. */
    @Override
    public void accept(TraceInput input) {
        if (gate.pass(input, live)) {
            writer.offer(input);
        }
    }

    /**
     * Stops the writer and waits for its last fold. An input after this reaches the live inputs alone,
     * never the journal.
     */
    @Override
    public void close() {
        writer.stop();
        try {
            thread.join(CLOSE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
