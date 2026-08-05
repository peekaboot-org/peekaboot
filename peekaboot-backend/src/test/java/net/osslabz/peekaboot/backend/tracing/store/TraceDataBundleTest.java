package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDataBundleTest {

    @Test
    void spansReturnsSortedSnapshot() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(createSpan("span3", 3), 100);
        bundle.addSpan(createSpan("span1", 1), 100);
        bundle.addSpan(createSpan("span2", 2), 100);

        List<SpanData> spans = bundle.spans();

        assertThat(spans).extracting(SpanData::spanId).containsExactly("span1", "span2", "span3");
    }

    @Test
    void addSpanTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addSpan(createSpan("span" + i, i), 3);
        }

        assertThat(bundle.spans()).extracting(SpanData::spanId).containsExactly("span3", "span4", "span5");
    }

    @Test
    void spansCanBeReadWhileSpansAreAdded() throws Exception {
        // The OTel batch exporter adds spans on its own thread while the
        // toolbar polls the API; reading must not throw
        // ConcurrentModificationException or return a corrupt snapshot.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        int totalSpans = 20_000;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < totalSpans; i++) {
                    bundle.addSpan(createSpan("span" + i, i), Integer.MAX_VALUE);
                }
            });

            while (!writer.isDone()) {
                List<SpanData> snapshot = bundle.spans();
                assertThat(snapshot).isSortedAccordingTo(Comparator.comparingLong(SpanData::creationOrder));
            }
            writer.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(bundle.spans()).hasSize(totalSpans);
    }

    @Test
    void addLogTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addLog(createLog("log" + i), 3);
        }

        assertThat(bundle.logs()).extracting(LogCapturedEvent::message).containsExactly("log3", "log4", "log5");
    }

    private LogCapturedEvent createLog(String message) {
        return new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", message, "main");
    }

    private SpanData createSpan(String spanId, long creationOrder) {
        return new SpanData(
                "trace1",
                spanId,
                null,
                "span-" + spanId,
                null,
                Instant.now(),
                Instant.now().plusMillis(10),
                Duration.ofMillis(10),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                creationOrder
        );
    }
}
