package net.osslabz.peekaboot.tracing.query;

import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceQueryServiceTest {

    private InMemorySpanStore store;
    private TraceQueryService queryService;

    @BeforeEach
    void setUp() {
        store = new InMemorySpanStore(100, 50);
        queryService = new TraceQueryService(store);
    }

    @Test
    void getErrorTraces_returnsOnlyTracesWithErrors() {
        // Create a normal span (no error)
        SpanData normalSpan = new SpanData(
                "trace-1", "span-1", null, "normal-op", null,
                Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                store.nextCreationOrder()
        );
        store.report(normalSpan);

        // Create an error span
        SpanData errorSpan = new SpanData(
                "trace-2", "span-2", null, "error-op", null,
                Instant.now(), Instant.now().plusMillis(200), Duration.ofMillis(200),
                Map.of(), List.of(), "Connection failed", "java.io.IOException",
                null, null, null, List.of(), store.nextCreationOrder()
        );
        store.report(errorSpan);

        List<TraceData> errorTraces = queryService.getErrorTraces(10);

        assertEquals(1, errorTraces.size());
        assertEquals("trace-2", errorTraces.get(0).traceId());
        assertTrue(errorTraces.get(0).hasErrors());
    }

    @Test
    void getErrorTraces_respectsLimit() {
        // Create 5 error traces
        for (int i = 0; i < 5; i++) {
            SpanData errorSpan = new SpanData(
                    "trace-" + i, "span-" + i, null, "error-op-" + i, null,
                    Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                    Map.of(), List.of(), "Error " + i, null,
                    null, null, null, List.of(), store.nextCreationOrder()
            );
            store.report(errorSpan);
        }

        List<TraceData> errorTraces = queryService.getErrorTraces(3);

        assertEquals(3, errorTraces.size());
    }

    @Test
    void getErrorTraces_returnsEmptyWhenNoErrors() {
        SpanData normalSpan = new SpanData(
                "trace-1", "span-1", null, "normal-op", null,
                Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                store.nextCreationOrder()
        );
        store.report(normalSpan);

        List<TraceData> errorTraces = queryService.getErrorTraces(10);

        assertTrue(errorTraces.isEmpty());
    }
}
