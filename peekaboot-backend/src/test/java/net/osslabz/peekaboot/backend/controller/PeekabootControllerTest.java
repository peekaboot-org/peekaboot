package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.domain.trace.CollectionFramework;
import net.osslabz.peekaboot.backend.domain.trace.RootActionType;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceListSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawData;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTabSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.service.ActuatorInsightsService;
import net.osslabz.peekaboot.backend.service.MetricsService;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.service.TraceInsightsService;
import net.osslabz.peekaboot.backend.service.TraceRawService;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    private TraceRawService traceRawService;
    private MetricsService metricsService;
    private PeekabootProperties properties;
    private ObjectProvider<PeekabootTracingProperties> tracingPropertiesProvider;
    private PeekabootTracingProperties tracingProperties;

    private PeekabootController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        actuatorService = mock(PeekabookActuatorService.class);
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        traceInsightsService = mock(TraceInsightsService.class);
        traceRawService = mock(TraceRawService.class);
        metricsService = mock(MetricsService.class);
        properties = new PeekabootProperties();
        tracingPropertiesProvider = mock(ObjectProvider.class);
        tracingProperties = new PeekabootTracingProperties();

        when(tracingPropertiesProvider.getIfAvailable()).thenReturn(tracingProperties);
        when(metricsService.isAvailable()).thenReturn(true);

        controller = new PeekabootController(
                actuatorService,
                actuatorInsightsService,
                traceInsightsService,
                traceRawService,
                metricsService,
                properties,
                tracingPropertiesProvider
        );
    }

    @Nested
    class GetTracesRaw {

        @Test
        void shouldReturnTracesFromService() {
            TraceRawResponse expectedResponse = new TraceRawResponse(
                    CollectionFramework.BRAVE,
                    TraceRawSummary.empty(),
                    List.of()
            );
            when(traceRawService.getTraces(anyInt(), any()))
                    .thenReturn(expectedResponse);

            TraceRawResponse result = controller.getTracesRaw(50);

            assertThat(result.collectionFramework()).isEqualTo(CollectionFramework.BRAVE);
        }
    }

    @Nested
    class GetTracesInsights {

        @Test
        void shouldReturnInsightsResponse() {
            TraceInsightsResponse expectedResponse = new TraceInsightsResponse(
                    List.of(),
                    new TraceListSummary(0, 0, 0, 0.0)
            );
            when(traceInsightsService.getInsights(anyInt(), any(), any(), any()))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(100, null, null);

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassLimitToService() {
            TraceInsightsResponse expectedResponse = new TraceInsightsResponse(
                    List.of(),
                    new TraceListSummary(0, 0, 0, 0.0)
            );
            when(traceInsightsService.getInsights(25, TraceCaptureMode.ERRORS_ONLY, null, null))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(25, null, null);

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassFiltersToService() {
            TraceInsightsResponse expectedResponse = new TraceInsightsResponse(
                    List.of(),
                    new TraceListSummary(0, 0, 0, 0.0)
            );
            when(traceInsightsService.getInsights(100, TraceCaptureMode.ERRORS_ONLY, "SCHEDULED_JOB", "MyScheduler"))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(100, "SCHEDULED_JOB", "MyScheduler");

            assertThat(result).isEqualTo(expectedResponse);
        }
    }

    @Nested
    class GetTraceRaw {

        @Test
        void shouldReturnTraceWhenFound() {
            TraceRawData traceRawData = createTraceRawData("trace-123");
            when(traceRawService.getTrace("trace-123"))
                    .thenReturn(Optional.of(traceRawData));

            ResponseEntity<TraceRawData> result = controller.getTraceRaw("trace-123");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().traceId()).isEqualTo("trace-123");
        }

        @Test
        void shouldReturn404WhenTraceNotFound() {
            when(traceRawService.getTrace("unknown"))
                    .thenReturn(Optional.empty());

            ResponseEntity<TraceRawData> result = controller.getTraceRaw("unknown");

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
        void shouldIncludeTracingFeatureWhenTracingAvailable() {
            when(traceRawService.isTracingAvailable()).thenReturn(true);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("tracing")).isEqualTo(true);
        }

        @Test
        void shouldIncludeTracingFeatureAsFalseWhenTracingUnavailable() {
            // tracing disabled: the service bean exists but has no TraceQueryService
            when(traceRawService.isTracingAvailable()).thenReturn(false);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("tracing")).isEqualTo(false);
        }

        @Test
        void shouldIncludeMetricsFeature() {
            when(metricsService.isAvailable()).thenReturn(true);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("metrics")).isEqualTo(true);
        }

        @Test
        void shouldIncludeMetricsFeatureAsFalseWhenUnavailable() {
            when(metricsService.isAvailable()).thenReturn(false);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("metrics")).isEqualTo(false);
        }

        @Test
        void shouldIncludeTraceCaptureMode() {
            tracingProperties.setCaptureMode(TraceCaptureMode.ERRORS_ONLY);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("traceCaptureMode")).isEqualTo("ERRORS_ONLY");
        }
    }

    private TraceRawData createTraceRawData(String traceId) {
        return new TraceRawData(
                CollectionFramework.BRAVE,
                traceId,
                Instant.EPOCH,
                Instant.EPOCH.plusMillis(100),
                100L,
                new TraceRawData.PerTraceSummary(
                        new TraceRawSummary.CountDuration(1, 100L),
                        new TraceRawSummary.CountDuration(0, 0L),
                        new TraceRawSummary.Count(0),
                        new TraceRawSummary.Count(0)
                ),
                List.of(),
                List.of(),
                List.of(),
                null
        );
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
                List.of(),
                List.of()
        );

        return new TraceTree(
                traceId,
                0L,
                100L,
                TraceStatus.OK,
                RootActionType.HTTP_REQUEST,
                "test-operation",
                rootSpan,
                new TraceTabSummary(
                        null,
                        new TraceTabSummary.SpansSummary(1, 100L, 0),
                        new TraceTabSummary.QueriesSummary(0, 0L),
                        new TraceTabSummary.LogsSummary(0, 0, 0)
                ),
                Map.of()
        );
    }
}
