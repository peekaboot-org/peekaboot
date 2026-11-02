package org.peekaboot.backend.tracing.journal;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/** Stores restored from the trace files, the fold that writes them, and what a test compares between two of them. */
final class RestoredStores {

    private RestoredStores() {}

    static InMemoryTraceStore restored(JournalFiles files, Consumer<PeekabootTracingProperties> caps) {
        InMemoryTraceStore store = TraceStores.with(caps);
        TraceReplay.restore(files, store);
        return store;
    }

    /** A fold that replays into stores under {@code caps}. */
    static Checkpoint checkpoint(JournalFiles files, Consumer<PeekabootTracingProperties> caps) {
        return new Checkpoint(files, () -> TraceStores.with(caps));
    }

    static Checkpoint checkpoint(JournalFiles files) {
        return checkpoint(files, properties -> {});
    }

    /** The bucket's trace ids, newest first, as a listing shows them. */
    static List<String> idsIn(InMemoryTraceStore store, TraceBucket bucket) {
        return store.getTraces(bucket, Integer.MAX_VALUE).stream()
                .map(listed -> listed.bundle().traceId())
                .toList();
    }

    static List<String> messages(List<LogCapturedEvent> logs) {
        return logs.stream().map(LogCapturedEvent::message).toList();
    }

    /** What a reader of the trace sees; listing and log numbers aside, which a fold renumbers. */
    static List<Object> contentOf(TraceDataBundle bundle) {
        TraceData data = bundle.snapshot();
        return Arrays.asList(
                data.run(),
                data.spans(),
                data.complete(),
                data.interrupted(),
                data.failed(),
                data.droppedSpans(),
                data.startTime(),
                data.duration(),
                data.asyncSpanIds(),
                messages(bundle.capturedLogs().logs()),
                bundle.capturedLogs().droppedLogs(),
                bundle.request());
    }
}
