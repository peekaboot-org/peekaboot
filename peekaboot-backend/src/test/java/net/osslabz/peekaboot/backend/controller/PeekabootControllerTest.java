package net.osslabz.peekaboot.backend.controller;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceMetrics;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.service.ActuatorInsightsService;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.service.TraceInsightsService;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeekabootControllerTest {

    private PeekabookActuatorService actuatorService;
    private ActuatorInsightsService actuatorInsightsService;
    private TraceInsightsService traceInsightsService;
    private PeekabootProperties properties;
    private ObjectProvider<TraceQueryService> traceQueryServiceProvider;
    private ObjectProvider<PeekabootTracingProperties> tracingPropertiesProvider;
    private TraceQueryService traceQueryService;
    private PeekabootTracingProperties tracingProperties;

    private PeekabootController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        actuatorService = mock(PeekabookActuatorService.class);
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        traceInsightsService = mock(TraceInsightsService.class);
        properties = new PeekabootProperties();
        traceQueryServiceProvider = mock(ObjectProvider.class);
        tracingPropertiesProvider = mock(ObjectProvider.class);
        traceQueryService = mock(TraceQueryService.class);
        tracingProperties = new PeekabootTracingProperties();

        when(traceQueryServiceProvider.getIfAvailable()).thenReturn(traceQueryService);
        when(tracingPropertiesProvider.getIfAvailable()).thenReturn(tracingProperties);

        controller = new PeekabootController(
                actuatorService,
                actuatorInsightsService,
                traceInsightsService,
                properties,
                traceQueryServiceProvider,
                tracingPropertiesProvider
        );
    }

    @Nested
    class GetTracesRaw {

        @Test
        void shouldReturnTracesFromQueryService() {
            // Default mode is ERRORS_ONLY when devToolbar=false and captureMode=null
            TraceData traceData = createTraceData("trace-1", 100);
            when(traceQueryService.getTraces(50, TraceCaptureMode.ERRORS_ONLY))
                    .thenReturn(List.of(traceData));

            List<TraceData> result = controller.getTracesRaw(50);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).traceId()).isEqualTo("trace-1");
        }

        @Test
        void shouldReturnEmptyListWhenTraceQueryServiceIsNull() {
            when(traceQueryServiceProvider.getIfAvailable()).thenReturn(null);
            controller = new PeekabootController(
                    actuatorService,
                    actuatorInsightsService,
                    traceInsightsService,
                    properties,
                    traceQueryServiceProvider,
                    tracingPropertiesProvider
            );

            List<TraceData> result = controller.getTracesRaw(100);

            assertThat(result).isEmpty();
        }

        @Test
        void shouldUseExplicitCaptureMode() {
            tracingProperties.setCaptureMode(TraceCaptureMode.ALL);
            when(traceQueryService.getTraces(100, TraceCaptureMode.ALL))
                    .thenReturn(List.of());

            List<TraceData> result = controller.getTracesRaw(100);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class GetTracesInsights {

        @Test
        void shouldReturnInsightsResponse() {
            TraceInsightsResponse expectedResponse = new TraceInsightsResponse(
                    List.of(),
                    new TraceSummary(0, 0, 0, 0.0)
            );
            when(traceInsightsService.getInsights(anyInt(), any()))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(100);

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassLimitToService() {
            // Default mode is ERRORS_ONLY when devToolbar=false
            TraceInsightsResponse expectedResponse = new TraceInsightsResponse(
                    List.of(),
                    new TraceSummary(0, 0, 0, 0.0)
            );
            when(traceInsightsService.getInsights(25, TraceCaptureMode.ERRORS_ONLY))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(25);

            assertThat(result).isEqualTo(expectedResponse);
        }
    }

    @Nested
    class GetTraceRaw {

        @Test
        void shouldReturnTraceWhenFound() {
            TraceData traceData = createTraceData("trace-123", 150);
            when(traceQueryService.getTrace("trace-123"))
                    .thenReturn(Optional.of(traceData));

            ResponseEntity<TraceData> result = controller.getTraceRaw("trace-123");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().traceId()).isEqualTo("trace-123");
        }

        @Test
        void shouldReturn404WhenTraceNotFound() {
            when(traceQueryService.getTrace("unknown"))
                    .thenReturn(Optional.empty());

            ResponseEntity<TraceData> result = controller.getTraceRaw("unknown");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldReturn404WhenTraceQueryServiceIsNull() {
            when(traceQueryServiceProvider.getIfAvailable()).thenReturn(null);
            controller = new PeekabootController(
                    actuatorService,
                    actuatorInsightsService,
                    traceInsightsService,
                    properties,
                    traceQueryServiceProvider,
                    tracingPropertiesProvider
            );

            ResponseEntity<TraceData> result = controller.getTraceRaw("trace-123");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class GetTraceInsights {

        @Test
        void shouldReturnTraceTreeWhenFound() {
            TraceTree traceTree = createTraceTree("trace-456");
            when(traceInsightsService.getTraceInsights("trace-456"))
                    .thenReturn(Optional.of(traceTree));

            ResponseEntity<TraceTree> result = controller.getTraceInsights("trace-456");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().traceId()).isEqualTo("trace-456");
        }

        @Test
        void shouldReturn404WhenTraceNotFound() {
            when(traceInsightsService.getTraceInsights("unknown"))
                    .thenReturn(Optional.empty());

            ResponseEntity<TraceTree> result = controller.getTraceInsights("unknown");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class GetFeatures {

        @Test
        void shouldIncludeTracingFeatureWhenServiceAvailable() {
            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("tracing")).isEqualTo(true);
        }

        @Test
        void shouldIncludeTracingFeatureAsFalseWhenServiceUnavailable() {
            when(traceQueryServiceProvider.getIfAvailable()).thenReturn(null);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("tracing")).isEqualTo(false);
        }

        @Test
        void shouldIncludeTraceCaptureMode() {
            tracingProperties.setCaptureMode(TraceCaptureMode.ERRORS_ONLY);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("traceCaptureMode")).isEqualTo("ERRORS_ONLY");
        }
    }

    private TraceData createTraceData(String traceId, long durationMs) {
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
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0
        );

        return TraceData.fromSpans(traceId, List.of(span));
    }

    private TraceTree createTraceTree(String traceId) {
        SpanNode rootSpan = new SpanNode(
                "span-" + traceId,
                "test-operation",
                "SERVER",
                0L,
                100L,
                "OK",
                List.of(),
                Map.of(),
                List.of()
        );

        return new TraceTree(
                traceId,
                0L,
                100L,
                TraceStatus.OK,
                "test-operation",
                rootSpan,
                new TraceMetrics(1, 0, 0L, 0, 0),
                Map.of()
        );
    }
}
