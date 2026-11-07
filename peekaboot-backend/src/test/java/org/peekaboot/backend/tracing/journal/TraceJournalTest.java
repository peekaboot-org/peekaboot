package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.journal.JournalTexts.line;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.testsupport.FailedTraces;
import org.peekaboot.backend.testsupport.Latches;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.digest.ErrorDigestFile;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.testsupport.LogCapture;

class TraceJournalTest {

    private static final Instant STARTED = Instant.parse("2026-10-31T08:00:00Z");
    private static final Duration FAST = Duration.ofMillis(10);

    @TempDir
    Path dir;

    private static TraceJournal start(InMemoryTraceStore store, Path directory, long maxJournalChars) {
        return TraceJournal.start(
                store,
                TraceInputs.into(store),
                new ErrorDigestFile(directory, new ErrorDigest()),
                directory,
                FAST,
                maxJournalChars,
                TraceStores::withDefaults);
    }

    /** A journal whose live inputs reach {@code digest} as well as the store, as the auto-configuration wires it. */
    private static TraceJournal startRecording(InMemoryTraceStore store, ErrorDigest digest, Path directory) {
        return TraceJournal.start(
                store,
                FailedTraces.recorder(store, digest).liveInputs(),
                new ErrorDigestFile(directory, digest),
                directory,
                FAST,
                Long.MAX_VALUE,
                TraceStores::withDefaults);
    }

    /** A live line seen in the store means the restore is done, since the gate holds live inputs until then. */
    private static void awaitRestored(TraceJournal journal, InMemoryTraceStore store) {
        journal.accept(new TraceInput.LogCaptured(untraced().saying("live").build()));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> store.untracedLogs().stream().anyMatch(log -> "live".equals(log.message())));
    }

    /** A store whose restore counts {@code replaying} down at a run line and waits there for {@code release}. */
    private static InMemoryTraceStore storeHoldingTheRestoreAtItsRunLine(
            CountDownLatch replaying, CountDownLatch release) {
        return new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void beginRun(@Nullable Instant startedAt) {
                if (startedAt != null) {
                    replaying.countDown();
                    Latches.awaitQuietly(release);
                }
                super.beginRun(startedAt);
            }
        };
    }

    private static TraceInput logged(String traceId, String message) {
        return new TraceInput.LogCaptured(log(traceId).saying(message).build());
    }

    private static List<String> messages(InMemoryTraceStore store, String traceId) {
        return store.getTrace(traceId).orElseThrow().capturedLogs().logs().stream()
                .map(LogCapturedEvent::message)
                .toList();
    }

    /** What one run's journal took, the next run's store shows: traces, logs, untraced lines, the run and what it cut off. */
    @Test
    void theNextRunRestoresWhatThisOneTook() {
        InMemoryTraceStore first = TraceStores.withDefaults();
        try (TraceJournal journal = start(first, dir, Long.MAX_VALUE)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(new TraceInput.SpanArrived(
                    span("root").in("done").at(STARTED, Duration.ofMillis(5)).build()));
            journal.accept(logged("done", "finished"));
            journal.accept(new TraceInput.SpanArrived(span("query")
                    .in("cut-off")
                    .parent("handler")
                    .at(STARTED, Duration.ofMillis(5))
                    .build()));
            journal.accept(
                    new TraceInput.LogCaptured(untraced().saying("startup").build()));
        }

        InMemoryTraceStore second = TraceStores.withDefaults();
        try (TraceJournal journal = start(second, dir, Long.MAX_VALUE)) {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> second.getTrace("cut-off").isPresent());

            TraceData cutOff = second.getTrace("cut-off").orElseThrow().snapshot();
            assertThat(messages(second, "done")).containsExactly("finished");
            assertThat(second.untracedLogs())
                    .extracting(LogCapturedEvent::message)
                    .containsExactly("startup");
            assertThat(cutOff.interrupted()).isTrue();
            assertThat(cutOff.run()).isEqualTo(STARTED);
        }
    }

    /**
     * A live input that arrives during the restore waits for it. The store below holds the restore at its
     * first run line until the live input has been handed in, so the order is the gate's doing.
     */
    @Test
    void aLiveInputDuringTheRestoreReachesTheStoreAfterTheRestoredOnes() {
        InMemoryTraceStore first = TraceStores.withDefaults();
        try (TraceJournal journal = start(first, dir, Long.MAX_VALUE)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(logged("t1", "restored"));
        }
        CountDownLatch replaying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryTraceStore second = storeHoldingTheRestoreAtItsRunLine(replaying, release);

        try (TraceJournal journal = start(second, dir, Long.MAX_VALUE)) {
            Latches.awaitQuietly(replaying);
            journal.accept(logged("t1", "live"));
            release.countDown();

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> second.getTrace("t1").isPresent()
                            && messages(second, "t1").size() == 2);
            assertThat(messages(second, "t1")).containsExactly("restored", "live");
        }
    }

    /**
     * Past the gate's capacity a live input is dropped before it reaches the store, so the journal says so
     * once the restore is done. The store below holds the restore until the inputs have been handed in.
     */
    @Test
    void reportsTheInputsTheRestoreGateDropped() {
        CountDownLatch restoring = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryTraceStore store = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void beginRun(@Nullable Instant startedAt) {
                restoring.countDown();
                Latches.awaitQuietly(release);
                super.beginRun(startedAt);
            }
        };

        try (LogCapture capture = LogCapture.attach(TraceJournal.class)) {
            try (TraceJournal journal = start(store, dir, Long.MAX_VALUE)) {
                Latches.awaitQuietly(restoring);
                for (int i = 0; i <= JournalWriter.CAPACITY; i++) {
                    journal.accept(new TraceInput.TraceDiscarded("t" + i));
                }
                release.countDown();
            }
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot traces: dropped 1 inputs that arrived while the stored traces were"
                                + " being restored");
            });
        }
    }

    /**
     * A held input that throws costs itself alone: the restore's end says so once, and the journal goes
     * on to write. The store below holds the restore until the inputs have been handed in.
     */
    @Test
    void reportsAHeldInputThatCouldNotBeAppliedAndKeepsWriting() {
        CountDownLatch restoring = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryTraceStore store = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void beginRun(@Nullable Instant startedAt) {
                restoring.countDown();
                Latches.awaitQuietly(release);
                super.beginRun(startedAt);
            }

            @Override
            public void addLog(LogCapturedEvent log) {
                if ("bad".equals(log.message())) {
                    throw new IllegalStateException("refused");
                }
                super.addLog(log);
            }
        };
        TraceInput after = logged("t1", "after");

        try (LogCapture capture = LogCapture.attach(TraceJournal.class)) {
            try (TraceJournal journal = start(store, dir, Long.MAX_VALUE)) {
                Latches.awaitQuietly(restoring);
                journal.accept(logged("t1", "bad"));
                journal.accept(after);
                release.countDown();
            }
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot traces: could not apply 1 inputs that arrived while the stored traces"
                                + " were being restored (java.lang.IllegalStateException: refused)");
            });
        }
        assertThat(messages(store, "t1")).containsExactly("after");
        assertThat(JournalTexts.lines(new JournalFiles(dir).snapshot())).contains(line(after));
    }

    /** Past the size limit the journal is folded while the application runs; nothing it took is lost. */
    @Test
    void foldsPastTheSizeLimitAndLosesNothing() throws Exception {
        InMemoryTraceStore first = TraceStores.withDefaults();
        try (TraceJournal journal = start(first, dir, 400)) {
            JournalFiles files = new JournalFiles(dir);
            // a fold replaces the journal file, so a read can meet it gone for a moment
            await().atMost(Duration.ofSeconds(5)).ignoreExceptions().until(() -> files.journalId() != null);
            String startedUnder = files.journalId();
            journal.accept(new TraceInput.RunStarted(STARTED));
            for (int i = 0; i < 20; i++) {
                journal.accept(logged("t1", "line " + i));
            }
            await().atMost(Duration.ofSeconds(5))
                    .ignoreExceptions()
                    .until(() -> !startedUnder.equals(files.journalId()));
        }

        InMemoryTraceStore second = TraceStores.withDefaults();
        try (TraceJournal journal = start(second, dir, Long.MAX_VALUE)) {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> second.getTrace("t1").isPresent());

            assertThat(messages(second, "t1")).hasSize(20).startsWith("line 0").endsWith("line 19");
        }
    }

    /** A directory that cannot be written costs persistence, never the live store. */
    @Test
    void aJournalThatCannotWriteStillFeedsTheStore() throws Exception {
        Path notADirectory = Files.createFile(dir.resolve("not-a-directory"));
        InMemoryTraceStore store = TraceStores.withDefaults();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            try (TraceJournal journal = start(store, notADirectory, Long.MAX_VALUE)) {
                journal.accept(new TraceInput.SpanArrived(span("root").in("t1").build()));

                await().atMost(Duration.ofSeconds(5))
                        .until(() -> store.getTrace("t1").isPresent());
            }
            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getLevel)
                    .isEqualTo(Level.WARN);
        }
    }

    /** A close folds what the journal took into the snapshot and leaves a journal holding only its header. */
    @Test
    void aCloseFoldsWhatTheJournalTook() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        TraceInput span = new TraceInput.SpanArrived(span("root").in("t1").build());
        try (TraceJournal journal = start(store, dir, Long.MAX_VALUE)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(span);
        }

        JournalFiles files = new JournalFiles(dir);
        assertThat(JournalTexts.lines(files.snapshot())).contains(line(span));
        assertThat(JournalTexts.lines(files.journal())).hasSize(1);
    }

    @Test
    void anInputAfterTheCloseReachesTheStoreAlone() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        TraceJournal journal = start(store, dir, Long.MAX_VALUE);
        journal.close();

        journal.accept(new TraceInput.SpanArrived(span("late").in("t1").build()));

        assertThat(store.getTrace("t1")).isPresent();
        assertThat(JournalTexts.lines(new JournalFiles(dir).journal())).hasSize(1);
    }

    /** A live failure passes the open restore gate to the digest as it does to the store. */
    @Test
    void aLiveFailureReachesTheDigest() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        ErrorDigest digest = new ErrorDigest();
        try (TraceJournal journal = startRecording(store, digest, dir)) {
            awaitRestored(journal, store);
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("failed", STARTED, "out of stock", 42)));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("failed", STARTED, 500)));

            await().atMost(Duration.ofSeconds(5)).until(() -> !digest.entries().isEmpty());
            assertThat(digest.entries().getFirst().lastTraceId()).isEqualTo("failed");
        }
    }

    /**
     * A root held while the restore runs completes a trace restored failed but incomplete, and counts it
     * once. The root answers 200, so only the restored handler can have failed the trace. The store below
     * holds the restore at its first run line until the root has been handed in, so the root is one the
     * gate held.
     */
    @Test
    void aHeldRootCompletingARestoredFailureCountsItOnce() {
        try (TraceJournal journal = start(TraceStores.withDefaults(), dir, Long.MAX_VALUE)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("failed", STARTED, "out of stock", 42)));
        }
        CountDownLatch replaying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryTraceStore second = storeHoldingTheRestoreAtItsRunLine(replaying, release);
        ErrorDigest digest = new ErrorDigest();

        try (TraceJournal journal = startRecording(second, digest, dir)) {
            Latches.awaitQuietly(replaying);
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("failed", STARTED, 200)));
            release.countDown();
            awaitRestored(journal, second);

            assertThat(digest.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.lastTraceId()).isEqualTo("failed");
                assertThat(entry.count()).isEqualTo(1);
            });
        }
    }

    /** Without errors.jsonl only the replay could fill the next run's digest, and the replay reaches the store alone. */
    @Test
    void aReplayedFailureIsNotRecordedAgain() throws IOException {
        InMemoryTraceStore first = TraceStores.withDefaults();
        ErrorDigest firstDigest = new ErrorDigest();
        try (TraceJournal journal = startRecording(first, firstDigest, dir)) {
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("failed", STARTED, "out of stock", 42)));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("failed", STARTED, 500)));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> !firstDigest.entries().isEmpty());
        }
        Files.delete(dir.resolve("errors.jsonl"));

        InMemoryTraceStore second = TraceStores.withDefaults();
        ErrorDigest secondDigest = new ErrorDigest();
        try (TraceJournal journal = startRecording(second, secondDigest, dir)) {
            awaitRestored(journal, second);

            assertThat(second.getTrace("failed")).isPresent();
            assertThat(secondDigest.entries()).isEmpty();
        }
    }

    /**
     * The next run gets the digest back from {@code errors.jsonl}, and replaying the failed trace into the
     * store counts nothing more: the run that captured it counted it.
     */
    @Test
    void theNextRunRestoresTheDigestAndCountsARestoredFailureNoMore() {
        InMemoryTraceStore first = TraceStores.withDefaults();
        ErrorDigest firstDigest = new ErrorDigest();
        try (TraceJournal journal = startRecording(first, firstDigest, dir)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("failed", STARTED, "out of stock", 42)));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("failed", STARTED, 500)));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> !firstDigest.entries().isEmpty());
        }

        InMemoryTraceStore second = TraceStores.withDefaults();
        ErrorDigest secondDigest = new ErrorDigest();
        try (TraceJournal journal = startRecording(second, secondDigest, dir)) {
            awaitRestored(journal, second);

            assertThat(second.getTrace("failed")).isPresent();
            assertThat(secondDigest.entries()).isEqualTo(firstDigest.entries());
            assertThat(secondDigest.entries().getFirst().runs()).containsExactly(STARTED);
        }
    }

    /**
     * A failure the gate held while the restore ran counts on top of the restored entry. The store below
     * holds the restore at its first run line until the failure has been handed in, so it is one the gate
     * held; a digest restored after the gate opened would keep the held failure's entry and lose the
     * restored count.
     */
    @Test
    void aHeldFailureCountsOnTopOfTheRestoredDigest() {
        try (TraceJournal journal = startRecording(TraceStores.withDefaults(), new ErrorDigest(), dir)) {
            journal.accept(new TraceInput.RunStarted(STARTED));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("restored", STARTED, "out of stock", 42)));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("restored", STARTED, 500)));
        }
        CountDownLatch replaying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryTraceStore second = storeHoldingTheRestoreAtItsRunLine(replaying, release);
        ErrorDigest digest = new ErrorDigest();

        try (TraceJournal journal = startRecording(second, digest, dir)) {
            Latches.awaitQuietly(replaying);
            journal.accept(new TraceInput.SpanArrived(FailedTraces.handler("held", STARTED, "out of stock", 42)));
            journal.accept(new TraceInput.SpanArrived(FailedTraces.root("held", STARTED, 500)));
            release.countDown();
            awaitRestored(journal, second);

            assertThat(digest.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.lastTraceId()).isEqualTo("held");
                assertThat(entry.count()).isEqualTo(2);
            });
        }
    }

    /** Closing writes what the digest took last; the cadence is an hour, so only the stop can have written it. */
    @Test
    void closingWritesTheDigest() {
        InMemoryTraceStore store = TraceStores.withDefaults();
        ErrorDigest digest = new ErrorDigest();
        TraceJournal journal = TraceJournal.start(
                store,
                FailedTraces.recorder(store, digest).liveInputs(),
                new ErrorDigestFile(dir, digest),
                dir,
                Duration.ofHours(1),
                Long.MAX_VALUE,
                TraceStores::withDefaults);
        awaitRestored(journal, store);
        journal.accept(new TraceInput.SpanArrived(FailedTraces.root("failed", STARTED, 500)));

        journal.close();

        assertThat(dir.resolve("errors.jsonl")).content().contains("\"lastTraceId\":\"failed\"");
    }
}
