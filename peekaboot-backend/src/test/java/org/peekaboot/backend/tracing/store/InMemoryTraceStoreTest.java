package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

class InMemoryTraceStoreTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryTraceStore storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
    }

    @Test
    void addSpan_storesSpan() {
        storage.addSpan(spanIn("trace1", "span1"));

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(1);
        assertThat(bundle.get().spans().getFirst().spanId()).isEqualTo("span1");
    }

    @Test
    void addLog_storesLog() {
        storage.addLog(log("trace1", "INFO", "test message"));

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().logs().getFirst().message()).isEqualTo("test message");
    }

    @Test
    void setRequest_storesRequest() {
        storage.setRequest(RequestCompletedEvents.request("trace1")
                .path("/api/test")
                .controller("TestController", "test")
                .durationMs(50)
                .build());

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().request()).isNotNull();
        assertThat(bundle.get().request().path()).isEqualTo("/api/test");
    }

    @Test
    void aggregatesMultipleEventsForSameTrace() {
        storage.addSpan(spanIn("trace1", "span1"));
        storage.addSpan(span("span2")
                .in("trace1")
                .parent("span1")
                .named("child")
                .order(storage.nextCreationOrder())
                .build());
        storage.addLog(log("trace1", "INFO", "log1"));
        storage.setRequest(RequestCompletedEvents.minimal("trace1"));

        var bundle = storage.getTrace("trace1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().spans()).hasSize(2);
        assertThat(bundle.get().logs()).hasSize(1);
        assertThat(bundle.get().request()).isNotNull();
    }

    @Test
    void addSpan_doesNotTruncateWhenDuplicateArtifactsPushRawArrivalsPastTheCap() {
        // Cap of 2 real spans; five raw arrivals (root + a duplicated child pair, twice)
        // would overflow a cap enforced before deduplication but not one enforced after.
        InMemoryTraceStore capped = new InMemoryTraceStore(100, 2, Duration.ofMinutes(5));
        SpanData root = span("root")
                .in("t1")
                .named("GET /orders")
                .order(capped.nextCreationOrder())
                .build();
        SpanData duplicate = jdbcDuplicate("dup1", "query1", "SELECT 1")
                .in("t1")
                .order(capped.nextCreationOrder())
                .build();
        SpanData realQuery = jdbcQuery("query1", "SELECT 1")
                .in("t1")
                .parent("root")
                .order(capped.nextCreationOrder())
                .build();

        capped.addSpan(root);
        capped.addSpan(duplicate);
        capped.addSpan(realQuery);

        var bundle = capped.getTrace("t1").orElseThrow();
        assertThat(bundle.spans()).hasSize(2);
        assertThat(bundle.truncated()).isFalse();
    }

    @Test
    void addSpan_marksTheBundleTruncatedOnceRealSpansExceedTheCap() {
        InMemoryTraceStore capped = new InMemoryTraceStore(100, 2, Duration.ofMinutes(5));
        for (int i = 1; i <= 3; i++) {
            capped.addSpan(span("s" + i)
                    .in("t1")
                    .named("op" + i)
                    .order(capped.nextCreationOrder())
                    .build());
        }

        var bundle = capped.getTrace("t1").orElseThrow();
        assertThat(bundle.spans()).hasSize(2);
        assertThat(bundle.truncated()).isTrue();
    }

    @Test
    void getTrace_returnsEmptyForUnknownTraceId() {
        var bundle = storage.getTrace("unknown");

        assertThat(bundle).isEmpty();
    }

    @Test
    void errorSpanClassifiesTraceIntoErrorBucket() {
        storage.addSpan(errorSpan(storage, "t1"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void errorLogClassifiesTraceIntoErrorBucket() {
        storage.addLog(log("t1", "ERROR", "boom"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void infoLogDoesNotClassifyTraceIntoErrorBucket() {
        storage.addLog(log("t1", "INFO", "fine"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10)).isEmpty();
    }

    @Test
    void slowTraceClassifiedWhenTotalDurationReachesThreshold() {
        InMemoryTraceStore store = storeWithSlowThreshold(100);
        store.addSpan(span("s1")
                .in("t1")
                .at(START, Duration.ofMillis(150))
                .order(store.nextCreationOrder())
                .build());

        assertThat(store.getTraces(TraceBucket.SLOW, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void fastTraceNotClassifiedAsSlow() {
        InMemoryTraceStore store = storeWithSlowThreshold(100);
        store.addSpan(span("s1")
                .in("t1")
                .at(START, Duration.ofMillis(50))
                .order(store.nextCreationOrder())
                .build());

        assertThat(store.getTraces(TraceBucket.SLOW, 10)).isEmpty();
    }

    @Test
    void slowDurationSpansMultipleSpans() {
        // two 60ms spans 60ms apart: total window 120ms >= 100ms threshold
        InMemoryTraceStore store = storeWithSlowThreshold(100);
        store.addSpan(span("s1")
                .in("t1")
                .at(START, Duration.ofMillis(60))
                .order(store.nextCreationOrder())
                .build());
        assertThat(store.getTraces(TraceBucket.SLOW, 10)).isEmpty();
        store.addSpan(span("s2")
                .in("t1")
                .parent("s1")
                .named("op2")
                .at(START.plusMillis(60), Duration.ofMillis(60))
                .order(store.nextCreationOrder())
                .build());

        assertThat(store.getTraces(TraceBucket.SLOW, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    @Test
    void classificationIsIdempotent() {
        storage.addSpan(errorSpan(storage, "t1"));
        storage.addSpan(span("s2")
                .in("t1")
                .error("boom", "java.lang.RuntimeException")
                .order(storage.nextCreationOrder())
                .build());
        storage.addLog(log("t1", "ERROR", "boom"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void getTracesAllReturnsNewestFirst() throws InterruptedException {
        storage.addSpan(spanIn("t1", "s1"));
        Thread.sleep(5); // createdAt has millisecond resolution; sleep to order deterministically
        storage.addSpan(spanIn("t2", "s2"));

        List<TraceDataBundle> all = storage.getTraces(TraceBucket.ALL, 10);
        assertThat(all).extracting(TraceDataBundle::traceId).containsExactly("t2", "t1");
    }

    @Test
    void getTracesAllBucketRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            storage.addSpan(spanIn("trace-" + i, "span-" + i));
        }

        assertThat(storage.getTraces(TraceBucket.ALL, 3)).hasSize(3);
    }

    @Test
    void getTracesErrorsBucketRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            storage.addSpan(errorSpan(storage, "trace-" + i));
        }

        assertThat(storage.getTraces(TraceBucket.ERRORS, 3)).hasSize(3);
    }

    @Test
    void getTraceCountPerBucket() {
        storage.addSpan(errorSpan(storage, "t1"));
        storage.addSpan(spanIn("t2", "s2"));

        assertThat(storage.getTraceCount(TraceBucket.ALL)).isEqualTo(2);
        assertThat(storage.getTraceCount(TraceBucket.ERRORS)).isEqualTo(1);
        assertThat(storage.getTraceCount(TraceBucket.SLOW)).isZero();
    }

    @Test
    void lastNEvictionDropsOldestErrorTrace() {
        InMemoryTraceStore store = TraceStores.with(p -> {
            p.setMaxErrorTraces(2);
            p.setMaxSlowTraces(2);
        });
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
        InMemoryTraceStore store = TraceStores.with(Duration.ofMillis(1), p -> {});
        store.addSpan(errorSpan(store, "t1"));
        Thread.sleep(10);
        store.cleanUp();

        assertThat(store.getTraceCount(TraceBucket.ALL)).isZero();
        assertThat(store.getTrace("t1")).isPresent();
        assertThat(store.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void lateEventAfterCacheEvictionReusesBucketBundle() throws InterruptedException {
        InMemoryTraceStore store = TraceStores.with(Duration.ofMillis(1), p -> {});
        store.addSpan(errorSpan(store, "t1"));
        Thread.sleep(10);
        store.cleanUp();

        store.addLog(log("t1", "INFO", "late"));

        TraceDataBundle bundle = store.getTrace("t1").orElseThrow();
        assertThat(bundle.spans()).hasSize(1); // original span still there - no fresh bundle
        assertThat(bundle.logs()).hasSize(1);
        assertThat(store.getTraces(TraceBucket.ERRORS, 10)).hasSize(1);
    }

    @Test
    void clearEmptiesAllBuckets() {
        storage.addSpan(errorSpan(storage, "t1"));
        storage.clear();

        assertThat(storage.getTraceCount(TraceBucket.ALL)).isZero();
        assertThat(storage.getTraceCount(TraceBucket.ERRORS)).isZero();
        assertThat(storage.getTraceCount(TraceBucket.SLOW)).isZero();
        assertThat(storage.getTrace("t1")).isEmpty();
    }

    @Test
    void logsAreCappedPerTrace() {
        InMemoryTraceStore store = TraceStores.with(p -> p.setMaxLogsPerTrace(3));
        for (int i = 1; i <= 5; i++) {
            store.addLog(log("t1", "INFO", "log" + i));
        }

        var bundle = store.getTrace("t1");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().logs()).extracting(LogCapturedEvent::message).containsExactly("log3", "log4", "log5");
    }

    /**
     * The three-argument constructor - the one the autoconfigure and testing-app fixtures
     * use - takes every limit it is not given from {@link PeekabootTracingProperties}, so
     * those defaults have exactly one owner. Checked through behaviour at the two limits a
     * test can reach cheaply: the slow-trace threshold and the per-trace log cap.
     */
    @Test
    void threeArgumentConstructorTakesTheRemainingLimitsFromTheTracingPropertiesDefaults() {
        PeekabootTracingProperties defaults = new PeekabootTracingProperties();
        InMemoryTraceStore store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
        long threshold = defaults.getSlowTraceThresholdMs();
        store.addSpan(span("s1")
                .in("just-under")
                .at(START, Duration.ofMillis(threshold - 1))
                .order(store.nextCreationOrder())
                .build());
        store.addSpan(span("s2")
                .in("at-threshold")
                .at(START, Duration.ofMillis(threshold))
                .order(store.nextCreationOrder())
                .build());
        for (int i = 0; i <= defaults.getMaxLogsPerTrace(); i++) {
            store.addLog(log("logged", "INFO", "log" + i));
        }

        assertThat(store.getTraces(TraceBucket.SLOW, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("at-threshold");
        assertThat(store.getTrace("logged").orElseThrow().logs()).hasSize(defaults.getMaxLogsPerTrace());
    }

    @Test
    void lowercaseErrorLogAfterNonErrorLogClassifiesTraceIntoErrorBucket() {
        storage.addLog(log("t1", "INFO", "fine"));
        storage.addLog(log("t1", "error", "boom"));

        assertThat(storage.getTraces(TraceBucket.ERRORS, 10))
                .extracting(TraceDataBundle::traceId)
                .containsExactly("t1");
    }

    private static InMemoryTraceStore storeWithSlowThreshold(long slowTraceThresholdMs) {
        return TraceStores.with(p -> p.setSlowTraceThresholdMs(slowTraceThresholdMs));
    }

    /** A 100ms span with nothing else about it, numbered by the fixture store. */
    private SpanData spanIn(String traceId, String spanId) {
        return span(spanId)
                .in(traceId)
                .at(START, Duration.ofMillis(100))
                .order(storage.nextCreationOrder())
                .build();
    }

    private static SpanData errorSpan(InMemoryTraceStore store, String traceId) {
        return span(traceId + "-s")
                .in(traceId)
                .error("boom", "java.lang.RuntimeException")
                .order(store.nextCreationOrder())
                .build();
    }

    private static LogCapturedEvent log(String traceId, String level, String message) {
        return new LogCapturedEvent(traceId, "s1", START, level, "Logger", message, "main");
    }
}
