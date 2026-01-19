package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.SpanDataEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDataStorageTest {

    private TraceDataStorage storage;

    @BeforeEach
    void setUp() {
        storage = new TraceDataStorage(100, 50, Duration.ofMinutes(5));
    }

    @Test
    void onSpanData_storesSpan() {
        var spanData = createSpanData("trace1", "span1", null, "test");
        var event = new SpanDataEvent(spanData);

        storage.onSpanData(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(1);
        assertThat(bundle.get().spans().getFirst().spanId()).isEqualTo("span1");
    }

    @Test
    void onLogCaptured_storesLog() {
        var event = new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", "test message", "main");

        storage.onLogCaptured(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().logs().getFirst().message()).isEqualTo("test message");
    }

    @Test
    void onRequestCompleted_storesRequest() {
        var event = new RequestCompletedEvent(
                "trace1", "GET", "/api/test", null,
                Map.of(), null, false,
                "TestController", "test",
                Map.of(), Map.of(), List.of(),
                200, Map.of(), 50
        );

        storage.onRequestCompleted(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().request()).isNotNull();
        assertThat(bundle.get().request().path()).isEqualTo("/api/test");
    }

    @Test
    void aggregatesMultipleEventsForSameTrace() {
        storage.onSpanData(new SpanDataEvent(createSpanData("trace1", "span1", null, "root")));
        storage.onSpanData(new SpanDataEvent(createSpanData("trace1", "span2", "span1", "child")));
        storage.onLogCaptured(new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "Test", "log1", "main"));
        storage.onRequestCompleted(new RequestCompletedEvent(
                "trace1", "GET", "/test", null,
                Map.of(), null, false,
                null, null,
                Map.of(), Map.of(), List.of(),
                200, Map.of(), 100
        ));

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(2);
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().request()).isNotNull();
    }

    @Test
    void getRecentTraces_returnsOrderedByCreation() throws InterruptedException {
        storage.onSpanData(new SpanDataEvent(createSpanData("trace1", "span1", null, "first")));
        Thread.sleep(10);
        storage.onSpanData(new SpanDataEvent(createSpanData("trace2", "span2", null, "second")));

        var recent = storage.getRecentTraces(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).traceId()).isEqualTo("trace2");
        assertThat(recent.get(1).traceId()).isEqualTo("trace1");
    }

    @Test
    void onSpanData_ignoresNullEvent() {
        storage.onSpanData(null);

        assertThat(storage.getTraceCount()).isZero();
    }

    @Test
    void getTrace_returnsEmptyForUnknownTraceId() {
        var bundle = storage.getTrace("unknown");

        assertThat(bundle).isEmpty();
    }

    @Test
    void clear_removesAllTraces() {
        storage.onSpanData(new SpanDataEvent(createSpanData("trace1", "span1", null, "test")));
        storage.onSpanData(new SpanDataEvent(createSpanData("trace2", "span2", null, "test")));

        storage.clear();

        assertThat(storage.getTraceCount()).isZero();
    }

    private SpanData createSpanData(String traceId, String spanId, String parentId, String name) {
        return new SpanData(
                traceId,
                spanId,
                parentId,
                name,
                null,
                Instant.now(),
                Instant.now().plusMillis(100),
                Duration.ofMillis(100),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                storage.nextCreationOrder()
        );
    }
}
