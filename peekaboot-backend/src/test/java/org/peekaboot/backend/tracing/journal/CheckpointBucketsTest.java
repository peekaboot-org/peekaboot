package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.journal.JournalTexts.writeJournal;
import static org.peekaboot.backend.tracing.journal.RestoredStores.checkpoint;
import static org.peekaboot.backend.tracing.journal.RestoredStores.contentOf;
import static org.peekaboot.backend.tracing.journal.RestoredStores.idsIn;
import static org.peekaboot.backend.tracing.journal.RestoredStores.messages;
import static org.peekaboot.backend.tracing.journal.RestoredStores.restored;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.Spans;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceInput;

/**
 * Generated journals under caps small enough that every bucket evicts, each folded partway and again at
 * the end, and restored from the fold and from the whole journal. Each seed is a fixed journal, so a
 * failure names one to replay.
 */
class CheckpointBucketsTest {

    private static final int JOURNALS = 2000;
    private static final Instant START = Instant.parse("2026-10-31T08:00:00Z");

    @TempDir
    Path dir;

    /**
     * A restore from the fold, with what the journal took since, lists the same traces in each bucket
     * in the same order as a restore from the whole journal, and each reads the same; so after a second
     * fold.
     */
    @Test
    void aRestoreAfterAFoldListsAndShowsWhatTheWholeJournalDoes() throws IOException {
        for (int seed = 0; seed < JOURNALS; seed++) {
            Random random = new Random(seed);
            Consumer<PeekabootTracingProperties> caps = capsFrom(random);
            List<TraceInput> inputs = inputsFrom(random);
            int foldAt = random.nextInt(inputs.size() + 1);
            JournalFiles whole = new JournalFiles(dir.resolve("whole-" + seed));
            writeJournal(whole, "j1", START, inputs);
            InMemoryTraceStore live = restored(whole, caps);

            JournalFiles files = new JournalFiles(dir.resolve("folded-" + seed));
            writeJournal(files, "j1", START, inputs.subList(0, foldAt));
            checkpoint(files, caps).fold().close();
            writeJournal(files, files.journalId(), START, inputs.subList(foldAt, inputs.size()));
            assertRestoresAs(live, files, caps, "seed " + seed + ", folded at " + foldAt);

            checkpoint(files, caps).fold().close();
            assertRestoresAs(live, files, caps, "seed " + seed + ", folded again");
        }
    }

    private static void assertRestoresAs(
            InMemoryTraceStore live, JournalFiles files, Consumer<PeekabootTracingProperties> caps, String journal) {
        InMemoryTraceStore folded = restored(files, caps);
        for (TraceBucket bucket : TraceBucket.values()) {
            assertThat(idsIn(folded, bucket)).as("%s, %s", bucket, journal).isEqualTo(idsIn(live, bucket));
        }
        for (TraceDataBundle bundle : live.storedTraces()) {
            assertThat(contentOf(folded.getTrace(bundle.traceId()).orElseThrow()))
                    .as("%s, %s", bundle.traceId(), journal)
                    .isEqualTo(contentOf(bundle));
        }
        assertThat(messages(folded.untracedLogs())).as(journal).isEqualTo(messages(live.untracedLogs()));
    }

    private static Consumer<PeekabootTracingProperties> capsFrom(Random random) {
        int maxTraces = 1 + random.nextInt(3);
        int maxErrorTraces = 1 + random.nextInt(2);
        int maxSlowTraces = 1 + random.nextInt(2);
        int maxSpans = 1 + random.nextInt(3);
        int maxLogs = 1 + random.nextInt(2);
        return properties -> {
            properties.setMaxTraces(maxTraces);
            properties.setMaxErrorTraces(maxErrorTraces);
            properties.setMaxSlowTraces(maxSlowTraces);
            properties.setMaxSpansPerTrace(maxSpans);
            properties.setMaxLogsPerTrace(maxLogs);
            properties.setMaxUntracedLogs(2);
            properties.setSlowTraceThresholdMs(100);
        };
    }

    /**
     * Spans, some async, failing or continuing a caller, and some arriving before the async entry span
     * they belong under, as they export; INFO and ERROR lines, a few outside any trace; requests; discards.
     */
    private static List<TraceInput> inputsFrom(Random random) {
        List<TraceInput> inputs = new ArrayList<>();
        Deque<SpanData> entriesToCome = new ArrayDeque<>();
        List<List<String>> spanIds = new ArrayList<>();
        for (int trace = 0; trace < 4; trace++) {
            spanIds.add(new ArrayList<>());
        }
        int count = 3 + random.nextInt(10);
        for (int i = 0; i < count; i++) {
            if (!entriesToCome.isEmpty() && random.nextBoolean()) {
                inputs.add(new TraceInput.SpanArrived(entriesToCome.remove()));
            }
            int trace = random.nextInt(4);
            String traceId = "t" + trace;
            int kind = random.nextInt(10);
            if (kind < 5) {
                inputs.add(new TraceInput.SpanArrived(spanFrom(random, traceId, i, spanIds.get(trace), entriesToCome)));
            } else if (kind < 8) {
                String level = random.nextInt(4) == 0 ? "ERROR" : "INFO";
                inputs.add(new TraceInput.LogCaptured(
                        log(traceId).at(level).saying("m" + i).build()));
            } else if (kind < 9) {
                inputs.add(new TraceInput.RequestCompleted(
                        RequestCompletedEvents.request(traceId).path("/p" + i).build()));
            } else if (random.nextInt(3) == 0) {
                inputs.add(new TraceInput.LogCaptured(untraced().saying("u" + i).build()));
            } else {
                inputs.add(new TraceInput.TraceDiscarded(traceId));
            }
        }
        entriesToCome.forEach(entry -> inputs.add(new TraceInput.SpanArrived(entry)));
        return inputs;
    }

    /** One span in five starts an async entry span of its own, which arrives later under the same parent. */
    private static SpanData spanFrom(
            Random random, String traceId, int index, List<String> earlier, Deque<SpanData> entriesToCome) {
        String spanId = traceId + "s" + index;
        Spans.SpanBuilder builder =
                random.nextInt(5) == 0 ? asyncTask(spanId) : span(spanId).named("n" + index);
        builder.in(traceId);
        String parent = null;
        if (!earlier.isEmpty() && random.nextBoolean()) {
            parent = earlier.get(random.nextInt(earlier.size()));
        } else if (random.nextInt(3) == 0) {
            parent = "caller" + index;
        }
        if (random.nextInt(5) == 0) {
            String entryId = traceId + "e" + index;
            Spans.SpanBuilder entry = asyncTask(entryId).in(traceId).at(START, Duration.ofMillis(random.nextInt(50)));
            entriesToCome.add((parent == null ? entry : entry.parent(parent)).build());
            earlier.add(entryId);
            parent = entryId;
        }
        if (parent != null) {
            builder.parent(parent);
        }
        if (random.nextInt(5) == 0) {
            builder.exception("IllegalStateException", "boom");
        }
        builder.at(START.plusMillis(random.nextInt(300)), Duration.ofMillis(random.nextInt(250)));
        earlier.add(spanId);
        return builder.build();
    }
}
