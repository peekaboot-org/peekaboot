package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.tracing.journal.JournalTexts.line;
import static org.peekaboot.backend.tracing.journal.JournalTexts.runLine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.testsupport.LogCapture;

class JournalWriterTest {

    private static final Instant STARTED = Instant.parse("2026-10-31T08:00:00Z");
    private static final Duration FAST = Duration.ofMillis(10);

    @TempDir
    Path dir;

    /** Each fold's journal as it stood, then the next journal: the writer's folding step, recorded. */
    private final List<List<String>> folded = new CopyOnWriteArrayList<>();

    /** How often the writer ran the step it takes beside each flush. */
    private final AtomicInteger flushesAlongside = new AtomicInteger();

    private JournalWriter writer(JournalFiles files, long maxJournalChars) {
        return writer(files, maxJournalChars, FAST, Clock.systemUTC());
    }

    private JournalWriter writer(JournalFiles files, long maxJournalChars, Duration flushInterval, Clock clock) {
        return new JournalWriter(
                files,
                () -> {
                    if (Files.exists(files.journal())) {
                        folded.add(JournalTexts.lines(files.journal()));
                    }
                    return files.startJournal();
                },
                flushInterval,
                maxJournalChars,
                clock,
                flushesAlongside::incrementAndGet);
    }

    private static TraceInput logged(String message) {
        return new TraceInput.LogCaptured(log("t1").saying(message).build());
    }

    /** The journal the last fold folded, without its header. */
    private List<String> lastFolded() {
        List<String> journal = folded.getLast();
        return journal.subList(1, journal.size());
    }

    private static boolean journalHolds(JournalFiles files, TraceInput input) {
        return Files.exists(files.journal())
                && JournalTexts.lines(files.journal()).contains(line(input));
    }

    private static void runToTheEnd(JournalWriter writer) throws InterruptedException {
        Thread thread = Thread.ofPlatform().start(writer::run);
        writer.stop();
        assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
    }

    /** Inputs that arrive before the run reports ready follow its run line all the same. */
    @Test
    void writesEachInputAfterTheRunLineOfItsRun() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        TraceInput first = logged("first");
        TraceInput second = logged("second");
        TraceInput third = logged("third");
        writer.offer(first);
        writer.offer(second);
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(third);

        runToTheEnd(writer);

        assertThat(lastFolded()).containsExactly(runLine(STARTED), line(first), line(second), line(third));
    }

    @Test
    void aRunThatNeverReportsReadyStillWritesItsInputs() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        TraceInput first = logged("first");
        TraceInput second = logged("second");
        writer.offer(first);
        writer.offer(second);

        runToTheEnd(writer);

        assertThat(lastFolded()).containsExactly(runLine(null), line(first), line(second));
    }

    /**
     * Each flush hands the lines to the operating system while the writer runs, so a crash usually loses
     * only the last interval.
     */
    @Test
    void flushesWithoutWaitingForAStop() throws InterruptedException {
        JournalFiles files = new JournalFiles(dir);
        JournalWriter writer = writer(files, Long.MAX_VALUE);
        TraceInput input = logged("flushed");
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(input);
        Thread thread = Thread.ofPlatform().start(writer::run);

        await().atMost(Duration.ofSeconds(5)).until(() -> journalHolds(files, input));

        writer.stop();
        assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
    }

    /**
     * Past the limit the journal is folded, and every next journal starts with the run line its lines
     * belong to. Stopped before it runs, the writer handles every input on this thread, so the folds
     * happen where the sizes say.
     */
    @Test
    void foldsOnceTheJournalOutgrowsItsLimit() {
        JournalWriter writer = writer(new JournalFiles(dir), 300);
        writer.offer(new TraceInput.RunStarted(STARTED));
        for (int i = 0; i < 5; i++) {
            writer.offer(logged("line " + i));
        }
        writer.stop();

        writer.run();

        assertThat(folded).hasSize(3);
        assertThat(folded).allSatisfy(journal -> assertThat(journal.get(1)).isEqualTo(runLine(STARTED)));
        assertThat(folded.stream().flatMap(List::stream).filter(text -> text.startsWith("{\"type\":\"log\"")))
                .hasSize(5);
    }

    /** Stopped before it ran, the writer still writes everything it had been handed. */
    @Test
    void aStopWritesWhatIsQueuedThenFolds() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        writer.offer(new TraceInput.RunStarted(STARTED));
        for (int i = 0; i < 50; i++) {
            writer.offer(logged("line " + i));
        }
        writer.stop();

        writer.run();

        assertThat(lastFolded()).hasSize(51).startsWith(runLine(STARTED)).endsWith(line(logged("line 49")));
    }

    /**
     * Nothing consumes while the test offers, so exactly the inputs past the capacity are dropped, and
     * said so once. The run start takes the first place, so nothing is held back for a run line.
     */
    @Test
    void aFullQueueDropsInsteadOfWaiting() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        writer.offer(new TraceInput.RunStarted(STARTED));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 1; i < JournalWriter.CAPACITY + 3; i++) {
                writer.offer(logged("line " + i));
            }
        });

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            Thread thread = Thread.ofPlatform().start(writer::run);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> !capture.appender().list.isEmpty());
            writer.stop();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(
                            Level.WARN,
                            "Peekaboot traces: the journal could not keep up and dropped 3 inputs;"
                                    + " they will not survive a restart"));
        }
    }

    /** A full queue still takes the run start, so the inputs held back for it follow its run line. */
    @Test
    void aFullQueueStillTakesTheRunStart() {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        for (int i = 0; i < JournalWriter.CAPACITY; i++) {
            writer.offer(logged("line " + i));
        }
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.stop();

        writer.run();

        assertThat(lastFolded())
                .hasSize(JournalWriter.CAPACITY + 1)
                .startsWith(runLine(STARTED), line(logged("line 0")));
    }

    /** A directory that cannot be written costs persistence for the run and one log line, nothing more. */
    @Test
    void anUnwritableDirectoryWarnsOnce() throws IOException, InterruptedException {
        Path notADirectory = Files.createFile(dir.resolve("not-a-directory"));
        JournalWriter writer = writer(new JournalFiles(notADirectory), Long.MAX_VALUE);
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(logged("lost"));

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            runToTheEnd(writer);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("Peekaboot traces: cannot write to " + notADirectory + " (")
                        .endsWith("); traces from here on will not survive a restart");
            });
        }
    }

    /**
     * Once writing has ended, a drop costs nothing more and is not logged either. A fixed clock and no
     * flush interval run the cadence after every input, so a drop would be reported if it could be.
     */
    @Test
    void aFailedWriteLeavesTheDropsUnreported() throws IOException {
        JournalFiles files = new JournalFiles(Files.createFile(dir.resolve("not-a-directory")));
        JournalWriter writer = new JournalWriter(
                files,
                files::startJournal,
                Duration.ZERO,
                Long.MAX_VALUE,
                Clock.fixed(STARTED, ZoneOffset.UTC),
                () -> {});
        for (int i = 0; i < JournalWriter.CAPACITY + 1; i++) {
            writer.offer(logged("line " + i));
        }
        writer.stop();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            writer.run();

            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .startsWith("Peekaboot traces: cannot write to ");
        }
    }

    /** A journal that fails as it is closed for a fold ends writing too: no fold follows, so no next journal. */
    @Test
    void aFailedCloseEndsWritingForTheRun() {
        AtomicInteger folds = new AtomicInteger();
        JournalWriter writer = new JournalWriter(
                new JournalFiles(dir),
                () -> {
                    folds.incrementAndGet();
                    return new OpenJournal(new StringWriter() {
                        @Override
                        public void close() throws IOException {
                            throw new IOException("disk full");
                        }
                    });
                },
                FAST,
                0,
                Clock.systemUTC(),
                () -> {});
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(logged("after the failure"));
        writer.stop();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            writer.run();

            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("java.io.IOException: disk full");
        }
        assertThat(folds).hasValue(1);
    }

    /** Stopped with a full queue, the writer still writes every input it holds. */
    @Test
    void aStopOnAFullQueueWritesEveryInput() {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        for (int i = 0; i < JournalWriter.CAPACITY; i++) {
            writer.offer(logged("line " + i));
        }
        writer.stop();

        writer.run();

        assertThat(lastFolded())
                .hasSize(JournalWriter.CAPACITY + 1)
                .startsWith(runLine(null))
                .endsWith(line(logged("line " + (JournalWriter.CAPACITY - 1))));
    }

    /** An interrupt ends the writer as a stop does: what it wrote is folded, and the interrupt is kept. */
    @Test
    void anInterruptEndsTheWriterAsAStopDoes() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE, Duration.ofHours(1), Clock.systemUTC());
        TraceInput input = logged("queued");
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(input);
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            writer.run();
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        // the writer waits with a timeout only on an empty queue, so both inputs are written by then
        await().atMost(Duration.ofSeconds(5)).until(() -> thread.getState() == Thread.State.TIMED_WAITING);

        thread.interrupt();

        assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        assertThat(lastFolded()).containsExactly(runLine(STARTED), line(input));
        assertThat(interruptKept).isTrue();
    }

    /** A clock set back holds no line back: the next flush is never further off than the interval. */
    @Test
    void aClockSetBackStillFlushes() throws InterruptedException {
        JournalFiles files = new JournalFiles(dir);
        SettableClock clock = new SettableClock();
        JournalWriter writer = writer(files, Long.MAX_VALUE, FAST, clock);
        TraceInput before = logged("before");
        TraceInput after = logged("after");
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(before);
        Thread thread = Thread.ofPlatform().start(writer::run);
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> journalHolds(files, before));

            clock.setBack(Duration.ofHours(1));
            writer.offer(after);

            await().atMost(Duration.ofSeconds(5)).until(() -> journalHolds(files, after));
        } finally {
            writer.stop();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /** A stop wakes a writer that is waiting for its next input. */
    @Test
    void aStopWakesAnIdleWriter() throws InterruptedException {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE, Duration.ofHours(1), Clock.systemUTC());
        Thread thread = Thread.ofPlatform().start(writer::run);
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> thread.getState() == Thread.State.TIMED_WAITING);

            writer.stop();

            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        } finally {
            thread.interrupt();
        }
    }

    /** An input whose line refuses it, as a log event without a message does, ends writing like a failed write. */
    @Test
    void anInputItsLineRefusesWarnsOnce() {
        JournalWriter writer = writer(new JournalFiles(dir), Long.MAX_VALUE);
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(new TraceInput.LogCaptured(log("t1").saying(null).build()));
        writer.stop();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            writer.run();

            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("java.lang.NullPointerException: message");
        }
    }

    /** An unchecked failure, from a fold or from encoding a line, ends writing as a checked one does. */
    @Test
    void anUncheckedFoldFailureWarnsOnce() {
        JournalWriter writer = new JournalWriter(
                new JournalFiles(dir),
                () -> {
                    throw new IllegalStateException("cannot fold");
                },
                FAST,
                Long.MAX_VALUE,
                Clock.systemUTC(),
                () -> {});
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.offer(logged("lost"));
        writer.stop();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            writer.run();

            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("java.lang.IllegalStateException: cannot fold");
        }
    }

    /** A write that fails unchecked warns once, and the broken journal is closed so its file is released. */
    @Test
    void anUncheckedWriteFailureClosesTheJournal() {
        FailingWriter out = new FailingWriter();
        JournalWriter writer = new JournalWriter(
                new JournalFiles(dir), () -> new OpenJournal(out), FAST, Long.MAX_VALUE, Clock.systemUTC(), () -> {});
        writer.offer(new TraceInput.RunStarted(STARTED));
        writer.stop();

        try (LogCapture capture = LogCapture.attach(JournalWriter.class)) {
            writer.run();

            assertThat(capture.appender().list)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("java.lang.IllegalStateException: cannot encode");
        }
        assertThat(out.closed).isTrue();
    }

    /** The stop runs the step beside the flush once more, so what the digest took last reaches its file. */
    @Test
    void theStopRunsTheStepBesideTheFlush() {
        JournalWriter writer = writer(
                new JournalFiles(dir), Long.MAX_VALUE, Duration.ofHours(1), Clock.fixed(STARTED, ZoneOffset.UTC));
        writer.stop();

        writer.run();

        assertThat(flushesAlongside).hasValue(1);
    }

    /** A long last fold can outlast the close's wait, so the stop writes the digest before it. */
    @Test
    void theStopRunsTheStepBesideTheFlushBeforeTheLastFold() {
        JournalFiles files = new JournalFiles(dir);
        List<String> steps = new CopyOnWriteArrayList<>();
        JournalWriter writer = new JournalWriter(
                files,
                () -> {
                    steps.add("fold");
                    return files.startJournal();
                },
                Duration.ofHours(1),
                Long.MAX_VALUE,
                Clock.fixed(STARTED, ZoneOffset.UTC),
                () -> steps.add("beside"));
        writer.stop();

        writer.run();

        assertThat(steps).as("the start's fold, the step, the stop's fold").containsExactly("fold", "beside", "fold");
    }

    /** A zero cadence on a fixed clock makes the one pass before the stop a due flush. */
    @Test
    void aDueFlushRunsTheStepBesideIt() {
        JournalWriter writer =
                writer(new JournalFiles(dir), Long.MAX_VALUE, Duration.ZERO, Clock.fixed(STARTED, ZoneOffset.UTC));
        writer.stop();

        writer.run();

        assertThat(flushesAlongside).as("the due flush's and the stop's").hasValue(2);
    }

    /** Every write fails unchecked, as an encoding failure would; it records whether it was closed. */
    private static final class FailingWriter extends Writer {

        private boolean closed;

        @Override
        public void write(char[] buffer, int offset, int length) {
            throw new IllegalStateException("cannot encode");
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            closed = true;
        }
    }

    /** The system clock, set back by hand as an NTP step would. */
    private static final class SettableClock extends Clock {

        private volatile Duration offset = Duration.ZERO;

        void setBack(Duration amount) {
            offset = offset.minus(amount);
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
