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
    void getTrace_returnsEmptyForUnknownTraceId() {
        var bundle = storage.getTrace("unknown");

        assertThat(bundle).isEmpty();
    }

    @Test
    void clear_removesAllTraces() {
        storage.addSpan(createSpanData("trace1", "span1", null, "test"));
        storage.addSpan(createSpanData("trace2", "span2", null, "test"));

        storage.clear();

        assertThat(storage.getTraceCount(TraceBucket.ALL)).isZero();
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

    private SpanData createSpanData(String traceId, String spanId, Instant start, Instant end, String errorClass) {
        return new SpanData(traceId, spanId, null, "op", null,
                start, end,
                (start != null && end != null) ? Duration.between(start, end) : null,
                Map.of(), List.of(), null, errorClass,
                null, null, null, List.of(), storage.nextCreationOrder());
    }

    @Test
    void errorSpanClassifiesTraceIntoErrorBucket() {
        storage.addSpan(createSpanData("t1", "s1", Instant.now(), Instant.now(), "java.lang.RuntimeException"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void errorLogClassifiesTraceIntoErrorBucket() {
        storage.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "ERROR", "Logger", "boom", "main"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void infoLogDoesNotClassifyTraceIntoErrorBucket() {
        storage.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "INFO", "Logger", "fine", "main"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10)).isEmpty();
    }

    @Test
    void slowTraceClassifiedWhenTotalDurationReachesThreshold() {
        // threshold in setUp fixture: use a store with slowTraceThresholdMs = 100
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5), 10, 10, 100);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        store.addSpan(new SpanData("t1", "s1", null, "op", null,
                start, start.plusMillis(150), Duration.ofMillis(150),
                Map.of(), List.of(), null, null, null, null, null, List.of(), store.nextCreationOrder()));

        assertThat(store.getTraces(TraceBucket.SLOW, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void fastTraceNotClassifiedAsSlow() {
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5), 10, 10, 100);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        store.addSpan(new SpanData("t1", "s1", null, "op", null,
                start, start.plusMillis(50), Duration.ofMillis(50),
                Map.of(), List.of(), null, null, null, null, null, List.of(), store.nextCreationOrder()));

        assertThat(store.getTraces(TraceBucket.SLOW, 10)).isEmpty();
    }

    @Test
    void slowDurationSpansMultipleSpans() {
        // two 60ms spans 60ms apart: total window 120ms >= 100ms threshold
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5), 10, 10, 100);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        store.addSpan(new SpanData("t1", "s1", null, "op", null,
                start, start.plusMillis(60), Duration.ofMillis(60),
                Map.of(), List.of(), null, null, null, null, null, List.of(), store.nextCreationOrder()));
        assertThat(store.getTraces(TraceBucket.SLOW, 10)).isEmpty();
        store.addSpan(new SpanData("t1", "s2", "s1", "op2", null,
                start.plusMillis(60), start.plusMillis(120), Duration.ofMillis(60),
                Map.of(), List.of(), null, null, null, null, null, List.of(), store.nextCreationOrder()));

        assertThat(store.getTraces(TraceBucket.SLOW, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void classificationIsIdempotent() {
        storage.addSpan(createSpanData("t1", "s1", Instant.now(), Instant.now(), "java.lang.RuntimeException"));
        storage.addSpan(createSpanData("t1", "s2", Instant.now(), Instant.now(), "java.lang.RuntimeException"));
        storage.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "ERROR", "Logger", "boom", "main"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void getTracesAllReturnsNewestFirst() throws InterruptedException {
        storage.addSpan(createSpanData("t1", "s1", Instant.now(), Instant.now(), null));
        Thread.sleep(5);  // createdAt has millisecond resolution; same pattern as getRecentTraceData_returnsOrderedByCreation
        storage.addSpan(createSpanData("t2", "s2", Instant.now(), Instant.now(), null));

        List<TraceDataBundle> all = storage.getTraces(TraceBucket.ALL, 10);
        assertThat(all).extracting(TraceDataBundle::traceId).containsExactly("t2", "t1");
    }

    @Test
    void getTracesAllBucketRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            storage.addSpan(createSpanData("trace-" + i, "span-" + i, null, "op-" + i));
        }

        assertThat(storage.getTraces(TraceBucket.ALL, 3)).hasSize(3);
    }

    @Test
    void getTracesErrorsBucketRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            storage.addSpan(createSpanData("trace-" + i, "span-" + i, Instant.now(), Instant.now(), "java.lang.RuntimeException"));
        }

        assertThat(storage.getTraces(TraceBucket.ERRORS, 3)).hasSize(3);
    }

    @Test
    void getTraceCountPerBucket() {
        storage.addSpan(createSpanData("t1", "s1", Instant.now(), Instant.now(), "java.lang.RuntimeException"));
        storage.addSpan(createSpanData("t2", "s2", Instant.now(), Instant.now(), null));

        assertThat(storage.getTraceCount(TraceBucket.ALL)).isEqualTo(2);
        assertThat(storage.getTraceCount(TraceBucket.ERRORS)).isEqualTo(1);
        assertThat(storage.getTraceCount(TraceBucket.SLOW)).isZero();
    }

    @Test
    void bucketFromParamIsLenient() {
        assertThat(TraceBucket.fromParam("errors")).isEqualTo(TraceBucket.ERRORS);
        assertThat(TraceBucket.fromParam("SLOW")).isEqualTo(TraceBucket.SLOW);
        assertThat(TraceBucket.fromParam(null)).isEqualTo(TraceBucket.ALL);
        assertThat(TraceBucket.fromParam("bogus")).isEqualTo(TraceBucket.ALL);
    }

    @Test
    void lastNEvictionDropsOldestErrorTrace() {
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5), 2, 2, 1000);
        store.addSpan(errorSpan(store, "t1"));
        store.addSpan(errorSpan(store, "t2"));
        store.addSpan(errorSpan(store, "t3"));

        assertThat(store.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t3", "t2");
    }

    @Test
    void errorTraceSurvivesAllCacheEviction() throws InterruptedException {
        // TTL-based eviction is deterministic, size-based (W-TinyLFU) is not
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMillis(1), 10, 10, 1000);
        store.addSpan(errorSpan(store, "t1"));
        Thread.sleep(10);
        store.cleanUp();

        assertThat(store.getTraceCount(TraceBucket.ALL)).isZero();
        assertThat(store.getTrace("t1")).isPresent();
        assertThat(store.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void lateEventAfterCacheEvictionReusesBucketBundle() throws InterruptedException {
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMillis(1), 10, 10, 1000);
        store.addSpan(errorSpan(store, "t1"));
        Thread.sleep(10);
        store.cleanUp();

        store.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "INFO", "Logger", "late", "main"));

        TraceDataBundle bundle = store.getTrace("t1").orElseThrow();
        assertThat(bundle.spans()).hasSize(1);   // original span still there — no fresh bundle
        assertThat(bundle.logs()).hasSize(1);
        assertThat(store.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void clearEmptiesAllBuckets() {
        storage.addSpan(createSpanData("t1", "s1", Instant.now(), Instant.now(), "java.lang.RuntimeException"));
        storage.clear();

        assertThat(storage.getTraceCount(TraceBucket.ALL)).isZero();
        assertThat(storage.getTraceCount(TraceBucket.ERRORS)).isZero();
        assertThat(storage.getTraceCount(TraceBucket.SLOW)).isZero();
        assertThat(storage.getTrace("t1")).isEmpty();
    }

    @Test
    void logsAreCappedPerTrace() {
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5),
                10, 10, 1000, 3);
        for (int i = 1; i <= 5; i++) {
            store.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "INFO", "Logger", "log" + i, "main"));
        }

        var bundle = store.getTrace("t1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).extracting(LogCapturedEvent::message)
                .containsExactly("log3", "log4", "log5");
    }

    @Test
    void lowercaseErrorLogAfterNonErrorLogClassifiesTraceIntoErrorBucket() {
        storage.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "INFO", "Logger", "fine", "main"));
        storage.addLog(new LogCapturedEvent("t1", "s1", Instant.now(), "error", "Logger", "boom", "main"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    private SpanData errorSpan(InMemoryTraceStore store, String traceId) {
        Instant now = Instant.now();
        return new SpanData(traceId, traceId + "-s", null, "op", null,
                now, now, Duration.ZERO, Map.of(), List.of(),
                "boom", "java.lang.RuntimeException",
                null, null, null, List.of(), store.nextCreationOrder());
    }
}
