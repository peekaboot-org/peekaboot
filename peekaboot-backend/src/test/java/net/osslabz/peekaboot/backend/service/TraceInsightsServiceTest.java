package net.osslabz.peekaboot.backend.service;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.config.UiTracingProperties;
import net.osslabz.peekaboot.backend.domain.trace.IssueType;
import net.osslabz.peekaboot.backend.domain.trace.SpanIssue;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceMetrics;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.mapper.trace.IssueDetector;
import net.osslabz.peekaboot.backend.mapper.trace.TraceTreeMapper;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import net.osslabz.peekaboot.tracing.store.TraceDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceInsightsServiceTest {

    private TraceQueryService traceQueryService;
    private TraceTreeMapper traceTreeMapper;
    private IssueDetector issueDetector;
    private TraceInsightsService service;

    @BeforeEach
    void setUp() {
        traceQueryService = mock(TraceQueryService.class);
        traceTreeMapper = new TraceTreeMapper();
        issueDetector = new IssueDetector(new UiTracingProperties());
        service = new TraceInsightsService(traceQueryService, null, traceTreeMapper, issueDetector);
    }

    @Test
    void getInsights_shouldTransformTracesAndCalculateSummary() {
        // Given: Two traces - one OK (100ms) and one with error (200ms)
        TraceData trace1 = createTraceData("trace1", 100, false);
        TraceData trace2 = createTraceData("trace2", 200, true);
        when(traceQueryService.getTraces(10, TraceCaptureMode.ALL)).thenReturn(List.of(trace1, trace2));

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceCaptureMode.ALL);

        // Then
        assertThat(response.traces()).hasSize(2);
        assertThat(response.summary().totalTraces()).isEqualTo(2);
        assertThat(response.summary().errorCount()).isEqualTo(1);
        assertThat(response.summary().avgDurationMs()).isEqualTo(150.0);
    }

    @Test
    void getInsights_shouldCountSlowTraces() {
        // Given: Three traces - one slow (150ms > 100ms threshold)
        TraceData fastTrace = createTraceData("fast", 50, false);
        TraceData slowTrace = createTraceData("slow", 150, false);
        TraceData normalTrace = createTraceData("normal", 80, false);
        when(traceQueryService.getTraces(10, TraceCaptureMode.ALL))
                .thenReturn(List.of(fastTrace, slowTrace, normalTrace));

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceCaptureMode.ALL);

        // Then: slowTrace has a span with 150ms duration, which triggers SLOW issue
        assertThat(response.summary().slowCount()).isEqualTo(1);
    }

    @Test
    void getInsights_shouldHandleEmptyTracesList() {
        // Given
        when(traceQueryService.getTraces(10, TraceCaptureMode.ALL)).thenReturn(List.of());

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceCaptureMode.ALL);

        // Then
        assertThat(response.traces()).isEmpty();
        assertThat(response.summary().totalTraces()).isEqualTo(0);
        assertThat(response.summary().errorCount()).isEqualTo(0);
        assertThat(response.summary().slowCount()).isEqualTo(0);
        assertThat(response.summary().avgDurationMs()).isEqualTo(0.0);
    }

    @Test
    void getInsights_shouldHandleNullTraceQueryService() {
        // Given: TraceQueryService is null (tracing not enabled)
        TraceInsightsService serviceWithNullQuery = new TraceInsightsService(null, null, traceTreeMapper, issueDetector);

        // When
        TraceInsightsResponse response = serviceWithNullQuery.getInsights(10, TraceCaptureMode.ALL);

        // Then
        assertThat(response.traces()).isEmpty();
        assertThat(response.summary().totalTraces()).isEqualTo(0);
    }

    @Test
    void getTraceInsights_shouldReturnTransformedTrace() {
        // Given
        TraceData traceData = createTraceData("trace1", 100, false);
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(traceData));

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("trace1");
        assertThat(result.get().durationMs()).isEqualTo(100L);
    }

    @Test
    void getTraceInsights_shouldDetectIssues() {
        // Given: A trace with a slow span (200ms > 100ms threshold)
        TraceData traceData = createTraceData("trace1", 200, false);
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(traceData));

        // When
        Optional<TraceTree> result = service.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().rootSpan().issues()).isNotEmpty();
        assertThat(result.get().rootSpan().issues())
                .extracting(SpanIssue::type)
                .contains(IssueType.SLOW);
    }

    @Test
    void getTraceInsights_shouldReturnEmptyForUnknownTraceId() {
        // Given
        when(traceQueryService.getTrace("unknown")).thenReturn(Optional.empty());

        // When
        Optional<TraceTree> result = service.getTraceInsights("unknown");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getTraceInsights_shouldHandleNullTraceQueryService() {
        // Given
        TraceInsightsService serviceWithNullQuery = new TraceInsightsService(null, null, traceTreeMapper, issueDetector);

        // When
        Optional<TraceTree> result = serviceWithNullQuery.getTraceInsights("trace1");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getInsights_shouldUseCorrectCaptureMode() {
        // Given: Set up mocks for ERRORS_ONLY mode
        TraceData errorTrace = createTraceData("error-trace", 100, true);
        when(traceQueryService.getTraces(5, TraceCaptureMode.ERRORS_ONLY)).thenReturn(List.of(errorTrace));

        // When
        TraceInsightsResponse response = service.getInsights(5, TraceCaptureMode.ERRORS_ONLY);

        // Then: Should have called with the correct mode and returned the error trace
        assertThat(response.traces()).hasSize(1);
        assertThat(response.summary().errorCount()).isEqualTo(1);
    }

    @Test
    void getInsights_shouldCalculateAverageDurationCorrectly() {
        // Given: Three traces with durations 100, 200, 300 -> avg = 200
        TraceData trace1 = createTraceData("trace1", 100, false);
        TraceData trace2 = createTraceData("trace2", 200, false);
        TraceData trace3 = createTraceData("trace3", 300, false);
        when(traceQueryService.getTraces(10, TraceCaptureMode.ALL))
                .thenReturn(List.of(trace1, trace2, trace3));

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceCaptureMode.ALL);

        // Then
        assertThat(response.summary().avgDurationMs()).isEqualTo(200.0);
    }

    @Test
    void getInsights_shouldCountTracesWithSlowOrVerySlowStatus() {
        // Given: Two traces - one with HAS_SLOW_SPANS status
        // Create a trace that will be mapped to HAS_SLOW_SPANS status - need an error span for HAS_ERRORS
        TraceData slowTrace = createTraceData("slow", 500, false); // 500ms will trigger VERY_SLOW
        TraceData normalTrace = createTraceData("normal", 50, false);
        when(traceQueryService.getTraces(10, TraceCaptureMode.ALL))
                .thenReturn(List.of(slowTrace, normalTrace));

        // When
        TraceInsightsResponse response = service.getInsights(10, TraceCaptureMode.ALL);

        // Then: slowTrace has VERY_SLOW issue, so slowCount should be 1
        assertThat(response.summary().slowCount()).isEqualTo(1);
    }

    @Test
    void getTraceInsights_shouldEnrichWithLogs() {
        // Given: TraceDataStorage with logs for the trace
        TraceDataStorage dataStorage = new TraceDataStorage();
        LogCapturedEvent logEvent = new LogCapturedEvent(
                "trace1",
                "span-trace1",
                Instant.now(),
                "INFO",
                "TestLogger",
                "Test log message from trace",
                "main"
        );
        dataStorage.accept(logEvent);

        TraceInsightsService serviceWithLogs = new TraceInsightsService(
                traceQueryService, dataStorage, traceTreeMapper, issueDetector);

        TraceData traceData = createTraceData("trace1", 100, false);
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(traceData));

        // When
        Optional<TraceTree> result = serviceWithLogs.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().logs()).isNotNull();
        assertThat(result.get().logs()).hasSize(1);
        assertThat(result.get().logs().get(0).message()).isEqualTo("Test log message from trace");
        assertThat(result.get().logs().get(0).level()).isEqualTo("INFO");
        assertThat(result.get().logs().get(0).loggerName()).isEqualTo("TestLogger");
    }

    @Test
    void getTraceInsights_shouldReturnEmptyLogsWhenNoLogsStored() {
        // Given: TraceDataStorage with no logs for this trace
        TraceDataStorage dataStorage = new TraceDataStorage();
        TraceInsightsService serviceWithStorage = new TraceInsightsService(
                traceQueryService, dataStorage, traceTreeMapper, issueDetector);

        TraceData traceData = createTraceData("trace1", 100, false);
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(traceData));

        // When
        Optional<TraceTree> result = serviceWithStorage.getTraceInsights("trace1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().logs()).isNullOrEmpty();
    }

    // Helper method to create test TraceData
    private TraceData createTraceData(String traceId, long durationMs, boolean hasError) {
        Instant start = Instant.EPOCH;
        Instant end = start.plusMillis(durationMs);

        SpanData span = new SpanData(
                traceId,
                "span-" + traceId,
                null,
                "test-operation",
                Span.Kind.SERVER,
                start,
                end,
                Duration.ofMillis(durationMs),
                Map.of(),
                List.of(),
                hasError ? "Test error" : null,
                hasError ? "TestException" : null,
                null,
                null,
                null,
                List.of(),
                0
        );

        return TraceData.fromSpans(traceId, List.of(span));
    }
}
