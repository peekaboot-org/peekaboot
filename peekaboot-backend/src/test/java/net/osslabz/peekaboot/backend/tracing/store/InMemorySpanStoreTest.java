package net.osslabz.peekaboot.backend.tracing.store;

import io.micrometer.tracing.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySpanStoreTest {

    private InMemorySpanStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySpanStore(10, 5);
    }

    @Test
    void shouldStoreAndRetrieveSpans() {
        SpanData span = createSpanData("trace-1", "span-1", "test-span");
        store.report(span);

        List<SpanData> spans = store.getSpansForTrace("trace-1");
        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().name()).isEqualTo("test-span");
    }

    @Test
    void shouldRetrieveTraceData() {
        store.report(createSpanData("trace-1", "span-1", "span-a"));
        store.report(createSpanData("trace-1", "span-2", "span-b"));

        Optional<TraceData> trace = store.getTrace("trace-1");
        assertThat(trace).isPresent();
        assertThat(trace.get().spanCount()).isEqualTo(2);
    }

    @Test
    void shouldEvictOldestTracesWhenCapacityExceeded() {
        // Store is configured with maxTraces=10 in setUp()
        for (int i = 0; i < 15; i++) {
            store.report(createSpanData("trace-" + i, "span-1", "span"));
        }

        // Force synchronous eviction cleanup
        store.cleanUp();

        // Should be at or below max capacity (10)
        assertThat(store.getTraceCount()).isLessThanOrEqualTo(10);

        // Oldest traces should be evicted, newest should remain
        // Traces 0-4 should be evicted, traces 10-14 should exist
        assertThat(store.getSpansForTrace("trace-0")).isEmpty();
        assertThat(store.getSpansForTrace("trace-14")).isNotEmpty();
    }

    @Test
    void shouldLimitSpansPerTrace() {
        for (int i = 0; i < 10; i++) {
            store.report(createSpanData("trace-1", "span-" + i, "span-" + i));
        }

        List<SpanData> spans = store.getSpansForTrace("trace-1");
        assertThat(spans).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void shouldReturnEmptyForNonExistentTrace() {
        List<SpanData> spans = store.getSpansForTrace("non-existent");
        assertThat(spans).isEmpty();

        Optional<TraceData> trace = store.getTrace("non-existent");
        assertThat(trace).isEmpty();
    }

    @Test
    void shouldGetRecentTraces() {
        for (int i = 0; i < 5; i++) {
            store.report(createSpanData("trace-" + i, "span-1", "span"));
        }

        List<TraceData> recent = store.getRecentTraces(3);
        assertThat(recent).hasSize(3);
    }

    @Test
    void shouldClearAllData() {
        store.report(createSpanData("trace-1", "span-1", "span"));
        store.report(createSpanData("trace-2", "span-1", "span"));

        store.clear();

        assertThat(store.getTraceCount()).isZero();
        assertThat(store.getAllTraces()).isEmpty();
    }

    private SpanData createSpanData(String traceId, String spanId, String name) {
        Instant now = Instant.now();
        return new SpanData(
                traceId,
                spanId,
                null,
                name,
                Span.Kind.SERVER,
                now,
                now.plusMillis(100),
                Duration.ofMillis(100),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder()
        );
    }
}
