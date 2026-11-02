package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.journal.JournalTexts.journalHeader;
import static org.peekaboot.backend.tracing.journal.JournalTexts.line;
import static org.peekaboot.backend.tracing.journal.JournalTexts.runLine;
import static org.peekaboot.backend.tracing.journal.JournalTexts.snapshotHeader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.tracing.Span;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.testsupport.LogCapture;

class TraceReplayTest {

    private static final Instant T1 = Instant.parse("2026-10-30T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-31T08:00:00Z");
    private static final Instant START = Instant.parse("2026-10-31T08:00:01Z");

    /** Small caps, so the inputs below evict, cap and trim as a busy application would. */
    private static void small(PeekabootTracingProperties properties) {
        properties.setMaxTraces(3);
        properties.setMaxErrorTraces(2);
        properties.setMaxSpansPerTrace(2);
        properties.setMaxLogsPerTrace(2);
        properties.setMaxUntracedLogs(2);
    }

    @TempDir
    Path dir;

    private static TraceInput spanned(String traceId, String spanId, long order) {
        return new TraceInput.SpanArrived(span(spanId)
                .in(traceId)
                .named(spanId)
                .kind(Span.Kind.SERVER)
                .at(START, Duration.ofMillis(10))
                .order(order)
                .build());
    }

    private static TraceInput child(String traceId, String spanId, String parentId, long order) {
        return new TraceInput.SpanArrived(span(spanId)
                .in(traceId)
                .named(spanId)
                .parent(parentId)
                .at(START, Duration.ofMillis(5))
                .order(order)
                .build());
    }

    private static TraceInput logged(String traceId, String level, String message) {
        return new TraceInput.LogCaptured(log(traceId).at(level).saying(message).build());
    }

    private static TraceInput untracedLine(String message) {
        return new TraceInput.LogCaptured(untraced().saying(message).build());
    }

    /** Uneven on purpose: five traces for three places, three spans for two, three logs for two, three untraced lines for two. */
    private static List<TraceInput> busyRun() {
        return List.of(
                spanned("a", "a1", 1),
                logged("a", "ERROR", "a failed"),
                untracedLine("u1"),
                child("b", "b1", "b0", 2),
                child("b", "b2", "b0", 3),
                spanned("b", "b0", 4),
                logged("b", "ERROR", "b failed"),
                logged("c", "INFO", "c1"),
                logged("c", "INFO", "c2"),
                logged("c", "WARN", "c3"),
                spanned("c", "c0", 5),
                new TraceInput.RequestCompleted(
                        RequestCompletedEvents.request("c").path("/c").build()),
                untracedLine("u2"),
                spanned("d", "d0", 6),
                new TraceInput.TraceDiscarded("d"),
                spanned("e", "e0", 7),
                untracedLine("u3"),
                spanned("f", "f0", 8));
    }

    /** The replay builds the store the live run built: the same buckets in the same order, the same caps, the same numbers. */
    @Test
    void aReplayBuildsTheStoreALiveRunBuilt() {
        InMemoryTraceStore live = TraceStores.with(TraceReplayTest::small);
        busyRun().forEach(TraceInputs.into(live)::accept);
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(
                files.journal(),
                Stream.concat(
                                Stream.of(journalHeader("j1"), runLine(T2)),
                                busyRun().stream().map(JournalTexts::line))
                        .toArray(String[]::new));
        InMemoryTraceStore restored = TraceStores.with(TraceReplayTest::small);

        TraceReplay.restore(files, restored);

        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(listing(restored, bucket)).as("%s", bucket).isEqualTo(listing(live, bucket));
        }
        for (TraceDataBundle bundle : live.storedTraces()) {
            assertThat(contentOf(restored.getTrace(bundle.traceId()).orElseThrow()))
                    .as(bundle.traceId())
                    .isEqualTo(contentOf(bundle));
        }
        assertThat(restored.untracedLogs()).isEqualTo(live.untracedLogs());
        assertThat(live.getTrace("b").orElseThrow().snapshot().droppedSpans())
                .as("b's span cap evicted one of its three spans")
                .isEqualTo(1);
    }

    private static List<String> listing(InMemoryTraceStore store, TraceBucket bucket) {
        return store.getTraces(bucket, Integer.MAX_VALUE).stream()
                .map(listed -> listed.bundle().traceId() + "@" + listed.listedAt() + "/" + listed.sequence())
                .toList();
    }

    private static List<Object> contentOf(TraceDataBundle bundle) {
        TraceData data = bundle.snapshot();
        List<Object> content = new ArrayList<>();
        content.add(data.spans());
        content.add(data.complete());
        content.add(data.failed());
        content.add(data.droppedSpans());
        content.add(bundle.capturedLogs());
        content.add(bundle.request());
        return content;
    }

    /** A crash in the middle of a write leaves the last line without its line break; that is expected and says nothing. */
    @Test
    void aLineCutShortByACrashIsSkippedQuietly() {
        JournalFiles files = new JournalFiles(dir);
        TraceInput kept = logged("t1", "INFO", "kept");
        JournalTexts.writeCutShort(
                files.journal(), journalHeader("j1"), runLine(T2), line(kept), "{\"type\":\"log\",\"tra");
        InMemoryTraceStore store = TraceStores.withDefaults();

        try (LogCapture capture = LogCapture.attach(TraceReplay.class, Level.DEBUG)) {
            TraceReplay.restore(files, store);

            assertThat(capture.appender().list).isEmpty();
        }
        assertThat(store.getTrace("t1").orElseThrow().capturedLogs().logs())
                .extracting(LogCapturedEvent::message)
                .containsExactly("kept");
    }

    @Test
    void damagedLinesAreSkippedAndCountedInOneLine() {
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(
                files.journal(),
                journalHeader("j1"),
                runLine(T2),
                line(logged("t1", "INFO", "first")),
                "{\"type\":\"log\",\"tra",
                "not json at all",
                line(logged("t1", "INFO", "second")));
        InMemoryTraceStore store = TraceStores.withDefaults();

        try (LogCapture capture = LogCapture.attach(TraceReplay.class, Level.INFO)) {
            TraceReplay.restore(files, store);

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(
                            Level.INFO,
                            "Peekaboot traces: skipped 2 unreadable lines in " + dir + "; restored the rest"));
        }
        assertThat(store.getTrace("t1").orElseThrow().capturedLogs().logs())
                .extracting(LogCapturedEvent::message)
                .containsExactly("first", "second");
    }

    /** A journal header of another version, and one too damaged to read. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":\"journal\",\"version\":99,\"id\":\"j1\"}", "{\"type\":\"journ"})
    void filesItCannotUseAreDiscardedWhole(String journalHeader) {
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(files.snapshot(), snapshotHeader(null), runLine(T1), line(spanned("a", "a1", 1)));
        JournalTexts.write(files.journal(), journalHeader, line(spanned("b", "b1", 2)));
        InMemoryTraceStore store = TraceStores.withDefaults();

        try (LogCapture capture = LogCapture.attach(TraceReplay.class, Level.INFO)) {
            TraceReplay.restore(files, store);

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(
                            Level.INFO,
                            "Peekaboot traces: the files in " + dir + " come from another Peekaboot version or start"
                                    + " with a damaged header; starting without them"));
        }
        assertThat(store.storedTraces()).isEmpty();
        assertThat(Files.exists(files.snapshot())).isFalse();
        assertThat(Files.exists(files.journal())).isFalse();
    }

    /** The live run begins all the same: the incomplete trace stored before the restore is interrupted by it. */
    @Test
    void aFileThatCannotBeReadIsSaidOnce() throws Exception {
        JournalFiles files = new JournalFiles(dir);
        Files.createDirectories(files.snapshot());
        InMemoryTraceStore store = TraceStores.withDefaults();
        store.addSpan(span("e1").in("earlier").parent("e0").build());

        try (LogCapture capture = LogCapture.attach(TraceReplay.class, Level.INFO)) {
            TraceReplay.restore(files, store);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .startsWith("Peekaboot traces: cannot read " + dir + " (")
                        .endsWith("); restored what was read before it");
            });
        }
        assertThat(store.getTrace("earlier").orElseThrow().snapshot().interrupted())
                .isTrue();
    }

    /** A fault in the store ends the replay at that line; what came before stays, and the live run begins. */
    @Test
    void aFaultWhileReplayingIsSaidOnceAndTheLiveRunStillBegins() {
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(
                files.journal(),
                journalHeader("j1"),
                runLine(T2),
                line(child("a", "a1", "a0", 1)),
                line(logged("a", "INFO", "boom")),
                line(child("b", "b1", "b0", 2)));
        InMemoryTraceStore store = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void addLog(LogCapturedEvent log) {
                if ("boom".equals(log.message())) {
                    throw new IllegalStateException("boom");
                }
                super.addLog(log);
            }
        };

        try (LogCapture capture = LogCapture.attach(TraceReplay.class, Level.INFO)) {
            TraceReplay.restore(files, store);

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(
                            Level.INFO,
                            "Peekaboot traces: cannot read " + dir
                                    + " (java.lang.IllegalStateException: boom); restored what was read before it"));
        }
        assertThat(store.getTrace("a").orElseThrow().snapshot().interrupted()).isTrue();
        assertThat(store.getTrace("b")).isEmpty();
    }

    /** A run that never reported ready has no start to tell it by, so each of its run lines begins a run. */
    @Test
    void runLinesWithoutAStartEachBeginARun() {
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(
                files.snapshot(),
                snapshotHeader(null),
                runLine(null),
                line(child("a", "a1", "a0", 5)),
                runLine(null),
                line(child("b", "b1", "b0", 3)));
        InMemoryTraceStore store = TraceStores.withDefaults();

        TraceReplay.restore(files, store);

        TraceData a = store.getTrace("a").orElseThrow().snapshot();
        TraceData b = store.getTrace("b").orElseThrow().snapshot();
        assertThat(List.of(a.interrupted(), b.interrupted())).containsExactly(true, true);
        assertThat(b.spans().getFirst().creationOrder()).isEqualTo(8);
    }

    /**
     * Each run line begins a run, a repeated one continues it, and the live run comes last. The orders show
     * it: each run's spans are raised past every earlier run's, and the repeated line raises nothing.
     */
    @Test
    void eachRunLineBeginsARunAndTheLiveRunEndsTheLast() {
        JournalFiles files = new JournalFiles(dir);
        JournalTexts.write(
                files.snapshot(),
                snapshotHeader(null),
                runLine(T1),
                line(child("a", "a1", "a0", 5)),
                runLine(T2),
                line(spanned("b", "b0", 3)));
        JournalTexts.write(files.journal(), journalHeader("j1"), runLine(T2), line(child("c", "c1", "c0", 4)));
        InMemoryTraceStore store = TraceStores.withDefaults();

        TraceReplay.restore(files, store);
        store.addSpan(span("live1")
                .in("live")
                .at(START, Duration.ofMillis(1))
                .order(1)
                .build());

        TraceData a = store.getTrace("a").orElseThrow().snapshot();
        TraceData b = store.getTrace("b").orElseThrow().snapshot();
        TraceData c = store.getTrace("c").orElseThrow().snapshot();
        assertThat(List.of(a.run(), b.run(), c.run())).containsExactly(T1, T2, T2);
        assertThat(List.of(a.interrupted(), b.interrupted(), c.interrupted())).containsExactly(true, false, true);
        assertThat(c.spans().getFirst().creationOrder()).isEqualTo(9);
        assertThat(store.getTrace("live")
                        .orElseThrow()
                        .snapshot()
                        .spans()
                        .getFirst()
                        .creationOrder())
                .isEqualTo(10);
    }
}
