package net.osslabz.peekaboot.tracing.store;

import net.osslabz.peekaboot.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.tracing.event.SpanCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDataStorageTest {

    private TraceDataStorage storage;

    @BeforeEach
    void setUp() {
        storage = new TraceDataStorage(100, Duration.ofMinutes(5));
    }

    @Test
    void accept_storesSpanEvent() {
        var event = new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null);

        storage.accept(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(1);
        assertThat(bundle.get().spans().getFirst().spanId()).isEqualTo("span1");
    }

    @Test
    void accept_storesLogEvent() {
        var event = new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", "test message", "main");

        storage.accept(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().logs().getFirst().message()).isEqualTo("test message");
    }

    @Test
    void accept_storesRequestEvent() {
        var event = new RequestCompletedEvent("trace1", "GET", "/api/test", 200, 50, Map.of(), Map.of(), Map.of(), "TestController", "test", null, false);

        storage.accept(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().request()).isNotNull();
        assertThat(bundle.get().request().path()).isEqualTo("/api/test");
    }

    @Test
    void accept_aggregatesMultipleEventsForSameTrace() {
        storage.accept(new SpanCompletedEvent("trace1", "span1", null, "root", null, 0, 100, Map.of(), null, null));
        storage.accept(new SpanCompletedEvent("trace1", "span2", "span1", "child", null, 10, 50, Map.of(), null, null));
        storage.accept(new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "Test", "log1", "main"));
        storage.accept(new RequestCompletedEvent("trace1", "GET", "/test", 200, 100, Map.of(), Map.of(), Map.of(), null, null, null, false));

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(2);
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().request()).isNotNull();
    }

    @Test
    void getRecentTraces_returnsOrderedByCreation() throws InterruptedException {
        storage.accept(new SpanCompletedEvent("trace1", "span1", null, "first", null, 0, 100, Map.of(), null, null));
        Thread.sleep(10);
        storage.accept(new SpanCompletedEvent("trace2", "span2", null, "second", null, 0, 100, Map.of(), null, null));

        var recent = storage.getRecentTraces(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).traceId()).isEqualTo("trace2");
        assertThat(recent.get(1).traceId()).isEqualTo("trace1");
    }

    @Test
    void accept_ignoresNullEvent() {
        storage.accept(null);

        assertThat(storage.getTraceCount()).isZero();
    }

    @Test
    void getTrace_returnsEmptyForUnknownTraceId() {
        var bundle = storage.getTrace("unknown");

        assertThat(bundle).isEmpty();
    }

    @Test
    void clear_removesAllTraces() {
        storage.accept(new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null));
        storage.accept(new SpanCompletedEvent("trace2", "span2", null, "test", null, 0, 100, Map.of(), null, null));

        storage.clear();

        assertThat(storage.getTraceCount()).isZero();
    }
}
