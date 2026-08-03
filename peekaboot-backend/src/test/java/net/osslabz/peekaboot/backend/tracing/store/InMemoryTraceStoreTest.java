package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTraceStoreTest {

    private InMemoryTraceStore storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
    }

    @Test
    void addSpan_storesSpan() {
        var spanData = createSpanData("trace1", "span1", null, "test");

        storage.addSpan(spanData);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(1);
        assertThat(bundle.get().spans().getFirst().spanId()).isEqualTo("span1");
    }

    @Test
    void addLog_storesLog() {
        var event = new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", "test message", "main");

        storage.addLog(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().logs().getFirst().message()).isEqualTo("test message");
    }

    @Test
    void setRequest_storesRequest() {
        var event = new RequestCompletedEvent(
                "trace1", "GET", "/api/test", null,
                Map.of(), null, false,
                "TestController", "test",
                Map.of(), Map.of(), List.of(),
                200, Map.of(), 50
        );

        storage.setRequest(event);

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().request()).isNotNull();
        assertThat(bundle.get().request().path()).isEqualTo("/api/test");
    }

    @Test
    void aggregatesMultipleEventsForSameTrace() {
        storage.addSpan(createSpanData("trace1", "span1", null, "root"));
        storage.addSpan(createSpanData("trace1", "span2", "span1", "child"));
        storage.addLog(new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "Test", "log1", "main"));
        storage.setRequest(new RequestCompletedEvent(
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
    void getRecentTraceData_returnsOrderedByCreation() throws InterruptedException {
        storage.addSpan(createSpanData("trace1", "span1", null, "first"));
        Thread.sleep(10);
        storage.addSpan(createSpanData("trace2", "span2", null, "second"));

        var recent = storage.getRecentTraceData(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).traceId()).isEqualTo("trace2");
        assertThat(recent.get(1).traceId()).isEqualTo("trace1");
    }

    @Test
    void getTrace_returnsEmptyForUnknownTraceId() {
        var bundle = storage.getTrace("unknown");

        assertThat(bundle).isEmpty();
    }

    @Test
    void clear_removesAllTraces() {
        storage.addSpan(createSpanData("trace1", "span1", null, "test"));
        storage.addSpan(createSpanData("trace2", "span2", null, "test"));

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
