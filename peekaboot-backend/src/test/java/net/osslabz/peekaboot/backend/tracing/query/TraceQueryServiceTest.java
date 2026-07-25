package net.osslabz.peekaboot.backend.tracing.query;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.backend.tracing.event.SpanDataEvent;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceQueryServiceTest {

    private TraceDataStorage storage;
    private TraceQueryService queryService;

    @BeforeEach
    void setUp() {
        storage = new TraceDataStorage(100, 50, Duration.ofMinutes(5));
        queryService = new TraceQueryService(storage);
    }

    private void addSpan(SpanData span) {
        storage.onSpanData(new SpanDataEvent(span));
    }

    @Test
    void getTraces_withErrorsOnlyMode_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            SpanData errorSpan = new SpanData(
                    "trace-" + i, "span-" + i, null, "error-op-" + i, null,
                    Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                    Map.of(), List.of(), "Error " + i, null,
                    null, null, null, List.of(), storage.nextCreationOrder()
            );
            addSpan(errorSpan);
        }

        List<TraceData> errorTraces = queryService.getTraces(3, TraceCaptureMode.ERRORS_ONLY);

        assertEquals(3, errorTraces.size());
    }

    @Test
    void getTraces_withErrorsOnlyMode_returnsEmptyWhenNoErrors() {
        SpanData normalSpan = new SpanData(
                "trace-1", "span-1", null, "normal-op", null,
                Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                storage.nextCreationOrder()
        );
        addSpan(normalSpan);

        List<TraceData> errorTraces = queryService.getTraces(10, TraceCaptureMode.ERRORS_ONLY);

        assertTrue(errorTraces.isEmpty());
    }

    @Test
    void getTraces_withErrorsOnlyMode_returnsOnlyErrorTraces() {
        SpanData normalSpan = new SpanData(
                "trace-1", "span-1", null, "normal-op", null,
                Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                storage.nextCreationOrder()
        );
        addSpan(normalSpan);

        SpanData errorSpan = new SpanData(
                "trace-2", "span-2", null, "error-op", null,
                Instant.now(), Instant.now().plusMillis(200), Duration.ofMillis(200),
                Map.of(), List.of(), "Error occurred", null,
                null, null, null, List.of(), storage.nextCreationOrder()
        );
        addSpan(errorSpan);

        List<TraceData> traces = queryService.getTraces(10, TraceCaptureMode.ERRORS_ONLY);

        assertEquals(1, traces.size());
        assertEquals("trace-2", traces.get(0).traceId());
    }

    @Test
    void getTraces_withAllMode_returnsAllTraces() {
        SpanData normalSpan = new SpanData(
                "trace-1", "span-1", null, "normal-op", null,
                Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                Map.of(), List.of(), null, null, null, null, null, List.of(),
                storage.nextCreationOrder()
        );
        addSpan(normalSpan);

        SpanData errorSpan = new SpanData(
                "trace-2", "span-2", null, "error-op", null,
                Instant.now(), Instant.now().plusMillis(200), Duration.ofMillis(200),
                Map.of(), List.of(), "Error occurred", null,
                null, null, null, List.of(), storage.nextCreationOrder()
        );
        addSpan(errorSpan);

        List<TraceData> traces = queryService.getTraces(10, TraceCaptureMode.ALL);

        assertEquals(2, traces.size());
    }

    @Test
    void getTraces_withAllMode_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            SpanData span = new SpanData(
                    "trace-" + i, "span-" + i, null, "op-" + i, null,
                    Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
                    Map.of(), List.of(), null, null, null, null, null, List.of(),
                    storage.nextCreationOrder()
            );
            addSpan(span);
        }

        List<TraceData> traces = queryService.getTraces(3, TraceCaptureMode.ALL);

        assertEquals(3, traces.size());
    }
}
