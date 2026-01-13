package net.osslabz.peekaboot.autoconfigure;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.controller.TracingController;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TracingControllerTest {

    InMemorySpanStore spanStore;
    TraceQueryService traceQueryService;
    TracingController controller;

    @BeforeEach
    void setUp() {
        spanStore = new InMemorySpanStore(100, 50);
        traceQueryService = new TraceQueryService(spanStore);
        PeekabootTracingProperties tracingProps = new PeekabootTracingProperties();
        PeekabootProperties props = new PeekabootProperties();
        controller = new TracingController(traceQueryService, tracingProps, props);
    }

    @Test
    void shouldReturnEmptyTracesListWhenNoTraces() {
        List<TraceData> traces = controller.getTraces(50);
        assertThat(traces).isEmpty();
    }

    @Test
    void shouldReturnTraceWhenTraceExists() {
        addTestTrace("test-trace-1");

        ResponseEntity<TraceData> response = controller.getTrace("test-trace-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isEqualTo("test-trace-1");
    }

    @Test
    void shouldReturn404ForUnknownTrace() {
        ResponseEntity<TraceData> response = controller.getTrace("nonexistent-trace");

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void shouldReturnTracesWithLimit() {
        addTestTrace("trace-1");
        addTestTrace("trace-2");
        addTestTrace("trace-3");

        List<TraceData> traces = controller.getTraces(2);

        assertThat(traces.size()).isLessThanOrEqualTo(2);
    }

    private void addTestTrace(String traceId) {
        Instant now = Instant.now();
        SpanData span = new SpanData(
                traceId,
                "span-1",
                null,
                "GET /test",
                Span.Kind.SERVER,
                now,
                now.plusMillis(50),
                Duration.ofMillis(50),
                Map.of("http.method", "GET", "http.url", "/test"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                System.nanoTime()
        );
        spanStore.report(span);
    }
}
