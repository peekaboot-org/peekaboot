package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.journal.JournalTexts.bucketLine;
import static org.peekaboot.backend.tracing.journal.JournalTexts.journalHeader;
import static org.peekaboot.backend.tracing.journal.JournalTexts.line;
import static org.peekaboot.backend.tracing.journal.JournalTexts.runLine;
import static org.peekaboot.backend.tracing.journal.JournalTexts.snapshotHeader;
import static org.peekaboot.backend.tracing.journal.JournalTexts.untracedLine;
import static org.peekaboot.backend.tracing.journal.RestoredStores.checkpoint;
import static org.peekaboot.backend.tracing.journal.RestoredStores.contentOf;
import static org.peekaboot.backend.tracing.journal.RestoredStores.idsIn;
import static org.peekaboot.backend.tracing.journal.RestoredStores.messages;
import static org.peekaboot.backend.tracing.journal.RestoredStores.restored;

import io.micrometer.tracing.Span;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceRemnant;

class CheckpointTest {

    private static final Instant T1 = Instant.parse("2026-10-30T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-31T08:00:00Z");
    private static final Instant START = Instant.parse("2026-10-31T08:00:01Z");

    /** Two spans and two logs a trace, three traces, two in Errors, two lines outside any trace. */
    private static void capped(PeekabootTracingProperties properties) {
        properties.setMaxSpansPerTrace(2);
        properties.setMaxLogsPerTrace(2);
        properties.setMaxTraces(3);
        properties.setMaxErrorTraces(2);
        properties.setMaxUntracedLogs(2);
    }

    @TempDir
    Path dir;

    /** Each call numbers a new span (the builder counts), so a test keeps the input it compares. */
    private static TraceInput spanned(String traceId, String spanId) {
        return new TraceInput.SpanArrived(span(spanId)
                .in(traceId)
                .named(spanId)
                .kind(Span.Kind.SERVER)
                .at(START, Duration.ofMillis(10))
                .build());
    }

    private static TraceInput logged(String traceId, String message) {
        return new TraceInput.LogCaptured(log(traceId).saying(message).build());
    }

    private static TraceInput discarded(String traceId) {
        return new TraceInput.TraceDiscarded(traceId);
    }

    /**
     * Under {@link #capped}: the failing span and the root are evicted, the first two logs trimmed. The
     * trace stays complete and failed, and its window still covers what was dropped.
     */
    private static List<TraceInput> overflowing(String traceId) {
        return List.of(
                new TraceInput.SpanArrived(span(traceId + "-charge")
                        .in(traceId)
                        .parent(traceId + "-root")
                        .named("charge")
                        .at(START, Duration.ofMillis(5))
                        .exception("IllegalStateException", "boom")
                        .build()),
                new TraceInput.SpanArrived(span(traceId + "-root")
                        .in(traceId)
                        .named("GET /a")
                        .kind(Span.Kind.SERVER)
                        .at(START, Duration.ofMillis(10))
                        .build()),
                new TraceInput.SpanArrived(span(traceId + "-late")
                        .in(traceId)
                        .parent(traceId + "-root")
                        .named("late")
                        .at(START.plusMillis(20), Duration.ofMillis(1))
                        .build()),
                new TraceInput.SpanArrived(span(traceId + "-later")
                        .in(traceId)
                        .parent(traceId + "-root")
                        .named("later")
                        .at(START.plusMillis(30), Duration.ofMillis(1))
                        .build()),
                logged(traceId, "one"),
                logged(traceId, "two"),
                logged(traceId, "three"),
                logged(traceId, "four"));
    }

    private static void writeJournal(JournalFiles files, List<TraceInput> inputs) {
        JournalTexts.writeJournal(files, "j1", T2, inputs);
    }

    /** The snapshot's lines between its header and the bucket lines that end it. */
    private static List<String> body(Path file) {
        List<String> lines = JournalTexts.lines(file);
        return lines.subList(1, lines.size() - TraceBucket.values().length);
    }

    private static List<String> bucketLines(Path file) {
        List<String> lines = JournalTexts.lines(file);
        return lines.subList(lines.size() - TraceBucket.values().length, lines.size());
    }

    @Test
    void keepsEveryLineOfALiveTraceInArrivalOrder() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput aSpan = spanned("a", "a1");
        TraceInput aLog = logged("a", "a says");
        TraceInput cSpan = spanned("c", "c1");
        writeJournal(files, List.of(aSpan, spanned("b", "b1"), aLog, cSpan, logged("b", "b says"), discarded("b")));

        checkpoint(files).fold().close();

        assertThat(JournalTexts.lines(files.snapshot()).getFirst()).isEqualTo(snapshotHeader("j1"));
        assertThat(body(files.snapshot())).containsExactly(runLine(T2), line(aSpan), line(aLog), line(cSpan));
    }

    /** A discard ends the bundle its lines built; what arrives after the last one is the trace now. */
    @Test
    void dropsWhatATraceHeldBeforeItsLastDiscard() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput aLate = logged("a", "a again");
        writeJournal(files, List.of(spanned("a", "a1"), logged("a", "a says"), discarded("a"), aLate));

        checkpoint(files).fold().close();

        assertThat(body(files.snapshot())).containsExactly(runLine(T2), line(aLate));
    }

    /**
     * A trace every bucket evicted begins again with its next line, like a discarded one. Its remnant
     * stands where that line was, so the restored trace enters the buckets where it entered them live.
     */
    @Test
    void aTraceEveryBucketEvictedBeginsAgainAtItsNextLine() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput zSpan = spanned("z", "z1");
        TraceInput xLatest = logged("x", "b");
        writeJournal(
                files,
                List.of(
                        new TraceInput.RequestCompleted(RequestCompletedEvents.minimal("x")),
                        spanned("y", "y1"),
                        zSpan,
                        logged("x", "a"),
                        xLatest));

        checkpoint(files, properties -> {
                    properties.setMaxTraces(2);
                    properties.setMaxLogsPerTrace(1);
                })
                .fold()
                .close();

        TraceRemnant trimmed = new TraceRemnant(0, 1, false, false, null, null, null, null, List.of());
        assertThat(body(files.snapshot()))
                .containsExactly(
                        runLine(T2),
                        line(zSpan),
                        JournalCodec.encode(new JournalLine.RemnantLine("x", trimmed)),
                        line(xLatest));
    }

    /**
     * The store keeps a trace's newest spans and logs and counts the rest; so does the fold. The remnant
     * line, where the trace's first line was, carries the counts and what the dropped inputs set.
     */
    @Test
    void writesOnlyWhatTheCapsKeptAndCarriesWhatTheyDropped() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        List<TraceInput> a = overflowing("a");
        writeJournal(files, a);

        checkpoint(files, CheckpointTest::capped).fold().close();

        TraceRemnant dropped =
                new TraceRemnant(2, 2, true, true, START, START.plusMillis(31), START, START.plusMillis(10), List.of());
        assertThat(body(files.snapshot()))
                .containsExactly(
                        runLine(T2),
                        JournalCodec.encode(new JournalLine.RemnantLine("a", dropped)),
                        line(a.get(2)),
                        line(a.get(3)),
                        line(a.get(6)),
                        line(a.get(7)));
    }

    /**
     * Live, the trace entered Errors at its ERROR line, which the log cap trimmed since, and the trace
     * that failed before it left Errors; it stays so after the fold, and failed.
     */
    @Test
    void aTraceKeepsItsPlaceInErrorsThoughTheLineThatFiledItWasTrimmed() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        logged("t", "a"),
                        new TraceInput.LogCaptured(
                                log("e").at("ERROR").saying("e failed").build()),
                        new TraceInput.LogCaptured(
                                log("t").at("ERROR").saying("b").build()),
                        logged("t", "c"),
                        logged("f", "f")));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxTraces(2);
            properties.setMaxErrorTraces(1);
            properties.setMaxLogsPerTrace(1);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.ERRORS)).containsExactly("t");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
        assertThat(after.getTrace("t").orElseThrow().snapshot().failed()).isTrue();
    }

    /**
     * The last line of the files filed {@code a} in Errors again after {@code b} had taken its place, and
     * the span that failed it was evicted; a fold of that fold still shows {@code a} failed and in Errors.
     */
    @Test
    void aFailureOnlyAnEvictedSpanShowedSurvivesTwoFolds() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput aLast =
                new TraceInput.SpanArrived(span("y").in("a").named("y").build());
        writeJournal(
                files,
                List.of(
                        new TraceInput.SpanArrived(span("x")
                                .in("a")
                                .named("x")
                                .exception("IllegalStateException", "boom")
                                .build()),
                        new TraceInput.SpanArrived(span("b1")
                                .in("b")
                                .named("b1")
                                .exception("IllegalStateException", "boom")
                                .build()),
                        aLast));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxSpansPerTrace(1);
            properties.setMaxErrorTraces(1);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.ERRORS)).containsExactly("a");
        assertThat(idsIn(after, TraceBucket.ERRORS)).isEqualTo(idsIn(before, TraceBucket.ERRORS));
        assertThat(after.getTrace("a").orElseThrow().snapshot().failed()).isTrue();
    }

    /**
     * Slow kept {@code t} while All evicted it, and {@code t}'s next line listed it in All again, after
     * {@code b}, which Errors keeps. The fold leaves out {@code gone}, which pushed {@code t} out of All,
     * so its replay keeps {@code t} listed from before and {@code u} evicts {@code t} in place of {@code b};
     * the bucket lines list All as it was, after one fold and after two.
     */
    @Test
    void aTraceThatEnteredAllAgainStaysListedThere() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        new TraceInput.SpanArrived(span("t1")
                                .in("t")
                                .named("t1")
                                .kind(Span.Kind.SERVER)
                                .at(START, Duration.ofSeconds(2))
                                .build()),
                        new TraceInput.RequestCompleted(RequestCompletedEvents.minimal("gone")),
                        new TraceInput.LogCaptured(
                                log("b").at("ERROR").saying("b failed").build()),
                        logged("t", "t again"),
                        logged("u", "u")));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxTraces(2);
            properties.setMaxSlowTraces(1);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);
        checkpoint(files, caps).fold().close();
        InMemoryTraceStore afterTwo = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.ALL)).containsExactly("u", "t");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
            assertThat(idsIn(afterTwo, bucket)).as("%s, folded twice", bucket).isEqualTo(idsIn(before, bucket));
        }
    }

    /**
     * {@code t} entered Slow live through a span the cap evicted since; {@code v} then evicted {@code u},
     * not {@code t}, and so it does after the fold.
     */
    @Test
    void aTraceSlowThroughAnEvictedSpanKeepsItsPlaceInSlow() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        slowOrNot("t", "t1", Duration.ofMillis(10)),
                        slowOrNot("u", "u1", Duration.ofSeconds(2)),
                        slowOrNot("t", "t2", Duration.ofSeconds(2)),
                        slowOrNot("t", "t3", Duration.ofMillis(10)),
                        slowOrNot("v", "v1", Duration.ofSeconds(2))));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxSpansPerTrace(1);
            properties.setMaxSlowTraces(2);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.SLOW)).containsExactly("v", "t");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
    }

    /**
     * {@code t}'s remnant carries a slow span the cap evicted, so {@code t} reads slow from where its
     * bundle began; live it entered Slow only when that span arrived, after {@code w} had left Slow. The
     * fold keeps {@code v}, which only Slow keeps, though {@code t} reads slow earlier in its replay.
     */
    @Test
    void aTraceSlowEarlierInTheReplayPushesNoOtherOut() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        slowOrNot("v", "v1", Duration.ofSeconds(2)),
                        new TraceInput.SpanArrived(span("w1")
                                .in("w")
                                .parent("we")
                                .named("w1")
                                .at(START, Duration.ofSeconds(2))
                                .build()),
                        slowOrNot("t", "t1", Duration.ofMillis(10)),
                        new TraceInput.SpanArrived(span("wr")
                                .in("w")
                                .named("GET /w")
                                .kind(Span.Kind.SERVER)
                                .at(START, Duration.ofMillis(10))
                                .build()),
                        new TraceInput.SpanArrived(asyncTask("we")
                                .in("w")
                                .parent("wr")
                                .at(START, Duration.ofMillis(5))
                                .build()),
                        slowOrNot("t", "t2", Duration.ofSeconds(2)),
                        slowOrNot("t", "t3", Duration.ofMillis(10)),
                        slowOrNot("t", "t4", Duration.ofMillis(10)),
                        slowOrNot("t", "t5", Duration.ofMillis(10))));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxTraces(2);
            properties.setMaxSlowTraces(2);
            properties.setMaxSpansPerTrace(3);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.SLOW)).containsExactly("t", "v");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
    }

    /**
     * Slow listed {@code t} as its bundle began, and kept it while {@code u} pushed it out of All; it left
     * Slow and entered it again later. Its bundle survives {@code u} in the fold's replay too, remnant
     * and all.
     */
    @Test
    void aTraceSlowKeptAsItBeganSurvivesTheTraceThatPushedItOutOfAll() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        new TraceInput.SpanArrived(asyncTask("t0")
                                .in("t")
                                .at(START, Duration.ofSeconds(2))
                                .build()),
                        new TraceInput.SpanArrived(span("u1")
                                .in("u")
                                .named("u1")
                                .exception("IllegalStateException", "boom")
                                .build()),
                        slowOrNot("t", "t1", Duration.ofMillis(10)),
                        slowOrNot("t", "t2", Duration.ofSeconds(2))));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxTraces(1);
            properties.setMaxErrorTraces(1);
            properties.setMaxSpansPerTrace(1);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(contentOf(after.getTrace("t").orElseThrow()))
                .isEqualTo(contentOf(before.getTrace("t").orElseThrow()));
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
    }

    /**
     * The remnant carries the whole trace's window, so in the fold's replay {@code t}, whose only span on
     * arrival is async, reads slow and pushes {@code v} out of Slow; live it never did, and after the
     * fold Slow lists {@code v} again.
     */
    @Test
    void aTraceSlowOnlyInTheReplayStaysOutOfSlow() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        slowOrNot("v", "v1", Duration.ofSeconds(2)),
                        logged("t", "a"),
                        new TraceInput.SpanArrived(asyncTask("t1")
                                .in("t")
                                .at(START, Duration.ofMillis(30))
                                .build()),
                        new TraceInput.SpanArrived(span("t2")
                                .in("t")
                                .named("GET /t")
                                .kind(Span.Kind.SERVER)
                                .at(START.minusMillis(1500), Duration.ofMillis(10))
                                .build()),
                        logged("t", "b")));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxSlowTraces(1);
            properties.setMaxLogsPerTrace(1);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.SLOW)).containsExactly("v");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
    }

    /**
     * Slow alone kept {@code f} once {@code y} pushed it out of All. Live, {@code u} had entered Slow
     * through a span the cap evicted since, and {@code y} left Slow again, so Slow kept both {@code u} and
     * {@code f}. The fold's replay, without that span, lists {@code u} in Slow later and evicts {@code f}
     * there instead; the bucket lines and the replay's held All keep {@code f}.
     */
    @Test
    void aTraceOnlySlowKeptSurvivesTheFold() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        slowOrNot("f", "f0", Duration.ofMillis(10)),
                        logged("u", "u0"),
                        slowOrNot("u", "u1", Duration.ofSeconds(2)),
                        slowOrNot("f", "f1", Duration.ofSeconds(2)),
                        slowOrNot("u", "u2", Duration.ofMillis(10)),
                        new TraceInput.SpanArrived(asyncTask("ya")
                                .in("y")
                                .parent("ys")
                                .at(START, Duration.ofSeconds(2))
                                .build()),
                        slowOrNot("y", "ys", Duration.ofMillis(10)),
                        slowOrNot("u", "u3", Duration.ofMillis(10)),
                        slowOrNot("u", "u4", Duration.ofMillis(10))));
        Consumer<PeekabootTracingProperties> caps = properties -> {
            properties.setMaxTraces(2);
            properties.setMaxSlowTraces(2);
            properties.setMaxSpansPerTrace(3);
        };
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        assertThat(idsIn(before, TraceBucket.SLOW)).containsExactly("u", "f");
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
        assertThat(contentOf(after.getTrace("f").orElseThrow()))
                .isEqualTo(contentOf(before.getTrace("f").orElseThrow()));
    }

    /** The snapshot ends with each bucket's traces, oldest first, as the replayed store lists them. */
    @Test
    void endsWithEachBucketsTracesInTheirOrder() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        spanned("a", "a1"),
                        slowOrNot("b", "b1", Duration.ofSeconds(2)),
                        new TraceInput.SpanArrived(span("c1")
                                .in("c")
                                .named("c1")
                                .exception("IllegalStateException", "boom")
                                .build()),
                        logged("a", "a again")));

        checkpoint(files).fold().close();

        assertThat(bucketLines(files.snapshot()))
                .containsExactly(
                        bucketLine(TraceBucket.ERRORS, "c"),
                        bucketLine(TraceBucket.SLOW, "b"),
                        bucketLine(TraceBucket.ALL, "a", "b", "c"));
    }

    private static TraceInput slowOrNot(String traceId, String spanId, Duration duration) {
        return new TraceInput.SpanArrived(span(spanId)
                .in(traceId)
                .named(spanId)
                .kind(Span.Kind.SERVER)
                .at(START, duration)
                .build());
    }

    /**
     * A span that outlived its async entry span stays async once the span cap evicts the entry; a fold
     * carries that, so the trace keeps the duration and the Slow listing it had.
     */
    @Test
    void aKeptSpanOfAnEvictedAsyncEntryStaysAsync() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(
                files,
                List.of(
                        new TraceInput.SpanArrived(asyncTask("e")
                                .in("a")
                                .parent("r")
                                .at(START, Duration.ofMillis(5))
                                .build()),
                        new TraceInput.SpanArrived(span("k")
                                .in("a")
                                .parent("e")
                                .named("k")
                                .at(START, Duration.ofSeconds(2))
                                .build()),
                        new TraceInput.SpanArrived(span("r")
                                .in("a")
                                .named("GET /a")
                                .kind(Span.Kind.SERVER)
                                .at(START, Duration.ofMillis(10))
                                .build())));
        Consumer<PeekabootTracingProperties> caps = properties -> properties.setMaxSpansPerTrace(2);
        InMemoryTraceStore before = restored(files, caps);

        checkpoint(files, caps).fold().close();
        InMemoryTraceStore after = restored(files, caps);

        TraceData live = before.getTrace("a").orElseThrow().snapshot();
        TraceData folded = after.getTrace("a").orElseThrow().snapshot();
        assertThat(live.duration()).isEqualTo(Duration.ofMillis(10));
        assertThat(folded.duration()).isEqualTo(live.duration());
        assertThat(folded.asyncSpanIds()).isEqualTo(live.asyncSpanIds());
        assertThat(idsIn(after, TraceBucket.SLOW)).isEqualTo(idsIn(before, TraceBucket.SLOW));
    }

    /**
     * A later request replaced the one that began the trace, so the line that began it is not copied;
     * a remnant line stands in its place, and the trace stays in the run it began in.
     */
    @Test
    void aTraceBegunByAReplacedRequestStaysInItsRun() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput second = new TraceInput.RequestCompleted(
                RequestCompletedEvents.request("x").path("/second").build());
        JournalTexts.write(
                files.journal(),
                journalHeader("j1"),
                runLine(T1),
                line(new TraceInput.RequestCompleted(
                        RequestCompletedEvents.request("x").path("/first").build())),
                runLine(T2),
                line(second));
        InMemoryTraceStore before = restored(files, properties -> {});

        checkpoint(files).fold().close();
        InMemoryTraceStore after = restored(files, properties -> {});

        TraceRemnant nothingDropped = new TraceRemnant(0, 0, false, false, null, null, null, null, List.of());
        assertThat(body(files.snapshot()))
                .containsExactly(
                        runLine(T1),
                        JournalCodec.encode(new JournalLine.RemnantLine("x", nothingDropped)),
                        runLine(T2),
                        line(second));
        assertThat(after.getTrace("x").orElseThrow().snapshot().run())
                .isEqualTo(before.getTrace("x").orElseThrow().snapshot().run())
                .isEqualTo(T1);
    }

    /** However many lines a trace took, the fold writes what its cap keeps. */
    @Test
    void aLogStormFoldsToWhatTheCapKeeps() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        List<TraceInput> storm = new ArrayList<>(List.of(spanned("a", "a1")));
        for (int i = 0; i < 1000; i++) {
            storm.add(logged("a", "storm " + i));
        }
        writeJournal(files, storm);

        checkpoint(files, properties -> properties.setMaxLogsPerTrace(5)).fold().close();

        assertThat(body(files.snapshot()))
                .hasSize(8)
                .endsWith(storm.subList(996, 1001).stream()
                        .map(JournalTexts::line)
                        .toArray(String[]::new));
    }

    /** The ring keeps the newest lines written outside any trace; so does the fold, and no more. */
    @Test
    void keepsTheNewestUntracedLinesTheRingCanHold() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput aSpan = spanned("a", "a1");
        writeJournal(
                files,
                List.of(
                        untracedLine("u1"),
                        untracedLine("u2"),
                        aSpan,
                        untracedLine("u3"),
                        untracedLine("u4"),
                        untracedLine("u5")));

        checkpoint(files, properties -> properties.setMaxUntracedLogs(2)).fold().close();

        assertThat(body(files.snapshot()))
                .containsExactly(runLine(T2), line(aSpan), line(untracedLine("u4")), line(untracedLine("u5")));
    }

    @Test
    void keepsARunLineOnlyWhereALineOfItsRunStays() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        TraceInput aSpan = spanned("a", "a1");
        JournalTexts.write(
                files.snapshot(),
                snapshotHeader(null),
                runLine(T1),
                line(spanned("gone", "g1")),
                line(discarded("gone")));
        JournalTexts.write(files.journal(), journalHeader("j1"), runLine(T2), line(aSpan));

        checkpoint(files).fold().close();

        assertThat(body(files.snapshot())).containsExactly(runLine(T2), line(aSpan));
    }

    /** The journal it absorbed stays named in the snapshot, and the next journal starts empty under a new id. */
    @Test
    void startsTheNextJournalUnderANewId() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        writeJournal(files, List.of(spanned("a", "a1")));

        checkpoint(files).fold().close();
        String next = files.journalId();

        assertThat(JournalTexts.lines(files.snapshot()).getFirst()).isEqualTo(snapshotHeader("j1"));
        assertThat(JournalTexts.lines(files.journal())).containsExactly(journalHeader(next));
        assertThat(next).isNotEqualTo("j1");
    }

    /** A crash after the snapshot moved into place and before the next journal started: nothing is replayed twice. */
    @Test
    void aCrashBeforeTheNextJournalReplaysNothingTwice() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        String[] journal = {journalHeader("j1"), runLine(T2), line(logged("a", "once"))};
        JournalTexts.write(files.journal(), journal);
        checkpoint(files).fold().close();
        JournalTexts.write(files.journal(), journal);
        InMemoryTraceStore restored = TraceStores.withDefaults();

        TraceReplay.restore(files, restored);

        assertThat(messages(restored.getTrace("a").orElseThrow().capturedLogs().logs()))
                .containsExactly("once");
    }

    /** Folding what a fold wrote changes nothing, its remnant line included. */
    @Test
    void aSecondFoldChangesNothing() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        List<TraceInput> inputs = new ArrayList<>(overflowing("a"));
        inputs.addAll(List.of(untracedLine("u1"), spanned("gone", "g1"), discarded("gone")));
        writeJournal(files, inputs);
        checkpoint(files, CheckpointTest::capped).fold().close();
        List<String> first = body(files.snapshot());

        checkpoint(files, CheckpointTest::capped).fold().close();

        assertThat(body(files.snapshot())).isEqualTo(first);
    }

    /**
     * The store restored from the folded files shows what it showed restored from the whole journal: the
     * same traces in each bucket, in the same order, the same spans, outcome facts, window and dropped
     * counts, the same logs and requests, the same untraced lines.
     */
    @Test
    void aStoreRestoredFromAFoldShowsWhatItShowedBefore() throws IOException {
        JournalFiles files = new JournalFiles(dir);
        List<TraceInput> inputs = new ArrayList<>(overflowing("a"));
        inputs.addAll(List.of(
                untracedLine("u1"),
                spanned("b", "b1"),
                logged("c", "c1"),
                spanned("c", "c0"),
                untracedLine("u2"),
                spanned("d", "d0"),
                discarded("d"),
                spanned("e", "e0"),
                untracedLine("u3"),
                spanned("f", "f0")));
        writeJournal(files, inputs);
        InMemoryTraceStore before = TraceStores.with(CheckpointTest::capped);
        TraceReplay.restore(files, before);

        checkpoint(files, CheckpointTest::capped).fold().close();
        InMemoryTraceStore after = TraceStores.with(CheckpointTest::capped);
        TraceReplay.restore(files, after);

        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(after, bucket)).as("%s", bucket).isEqualTo(idsIn(before, bucket));
        }
        for (TraceDataBundle bundle : before.storedTraces()) {
            assertThat(contentOf(after.getTrace(bundle.traceId()).orElseThrow()))
                    .as(bundle.traceId())
                    .isEqualTo(contentOf(bundle));
        }
        assertThat(messages(after.untracedLogs())).isEqualTo(messages(before.untracedLogs()));
    }
}
