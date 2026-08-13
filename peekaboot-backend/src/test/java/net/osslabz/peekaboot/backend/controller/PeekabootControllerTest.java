package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.actuator.raw.ActuatorRawResponse;
import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.domain.metrics.MetricsInfo;
import net.osslabz.peekaboot.backend.domain.trace.BucketCounts;
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
import net.osslabz.peekaboot.backend.service.PeekabootActuatorService;
import net.osslabz.peekaboot.backend.service.TraceInsightsService;
import net.osslabz.peekaboot.backend.service.TraceRawService;
import net.osslabz.peekaboot.backend.tracing.store.TraceBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeekabootControllerTest {

    private PeekabootActuatorService actuatorService;
    private ActuatorInsightsService actuatorInsightsService;
    private TraceInsightsService traceInsightsService;
    private TraceRawService traceRawService;
    private MetricsService metricsService;
    private PeekabootProperties properties;

    private PeekabootController controller;

    @BeforeEach
    void setUp() {
        actuatorService = mock(PeekabootActuatorService.class);
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        traceInsightsService = mock(TraceInsightsService.class);
        traceRawService = mock(TraceRawService.class);
        metricsService = mock(MetricsService.class);
        properties = new PeekabootProperties();

        when(metricsService.isAvailable()).thenReturn(true);

        controller = new PeekabootController(
                actuatorService,
                actuatorInsightsService,
                traceInsightsService,
                traceRawService,
                metricsService,
                properties
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

            TraceRawResponse result = controller.getTracesRaw(50, null);

            assertThat(result.collectionFramework()).isEqualTo(CollectionFramework.BRAVE);
        }

        @Test
        void shouldClampNegativeLimit() {
            // a negative limit would throw from Stream.limit and produce a 500
            controller.getTracesRaw(-5, null);

            verify(traceRawService).getTraces(eq(0), any());
        }

        @Test
        void shouldClampExcessiveLimit() {
            // huge limits would overflow downstream multiplications
            controller.getTracesRaw(Integer.MAX_VALUE, null);

            verify(traceRawService).getTraces(eq(10_000), any());
        }

        @Test
        void shouldPassParsedBucketToService() {
            controller.getTracesRaw(50, "errors");

            verify(traceRawService).getTraces(50, TraceBucket.ERRORS);
        }
    }

    @Nested
    class GetTracesInsights {

        @Test
        void shouldReturnInsightsResponse() {
            TraceInsightsResponse expectedResponse = emptyInsightsResponse();
            when(traceInsightsService.getInsights(anyInt(), any(), any(), any()))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(100, null, null, null);

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassLimitToService() {
            TraceInsightsResponse expectedResponse = emptyInsightsResponse();
            when(traceInsightsService.getInsights(25, TraceBucket.ALL, null, null))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(25, null, null, null);

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassFiltersToService() {
            TraceInsightsResponse expectedResponse = emptyInsightsResponse();
            when(traceInsightsService.getInsights(100, TraceBucket.ALL, "SCHEDULED_JOB", "MyScheduler"))
                    .thenReturn(expectedResponse);

            TraceInsightsResponse result = controller.getTracesInsights(100, null, "SCHEDULED_JOB", "MyScheduler");

            assertThat(result).isEqualTo(expectedResponse);
        }

        @Test
        void shouldPassParsedBucketToService() {
            controller.getTracesInsights(100, "errors", null, null);

            verify(traceInsightsService).getInsights(100, TraceBucket.ERRORS, null, null);
        }

        @Test
        void shouldFallBackToAllBucketForInvalidValue() {
            controller.getTracesInsights(100, "not-a-bucket", null, null);

            verify(traceInsightsService).getInsights(100, TraceBucket.ALL, null, null);
        }

        private TraceInsightsResponse emptyInsightsResponse() {
            return new TraceInsightsResponse(
                    List.of(),
                    new TraceListSummary(0, 0, 0, 0.0),
                    BucketCounts.empty()
            );
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
            // tracing disabled: the service bean exists but has no TraceStore
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
        void shouldNotIncludeTraceCaptureMode() {
            Map<String, Object> features = controller.getFeatures();

            assertThat(features).doesNotContainKey("traceCaptureMode");
        }
    }

    @Nested
    class GetInsights {

        @Test
        void shouldDefaultToEnglishWhenLocaleIsNull() {
            controller.getInsights(null);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH);
        }

        @Test
        void shouldDefaultToEnglishWhenLocaleIsBlank() {
            controller.getInsights("  ");

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH);
        }

        @Test
        void shouldReplaceUnderscoreWithHyphenBeforeParsingLocale() {
            controller.getInsights("en_US");

            verify(actuatorInsightsService).getInsights(Locale.forLanguageTag("en-US"));
        }

        @Test
        void shouldParseLocaleWithHyphenDirectly() {
            controller.getInsights("de-DE");

            verify(actuatorInsightsService).getInsights(Locale.forLanguageTag("de-DE"));
        }

        @Test
        void shouldReturnResponseFromService() {
            ActuatorInsightsResponse expected = new ActuatorInsightsResponse(
                    null, null, null, null, null, null, null, null, null, null);
            when(actuatorInsightsService.getInsights(any())).thenReturn(expected);

            ActuatorInsightsResponse result = controller.getInsights(null);

            assertThat(result).isSameAs(expected);
        }
    }

    @Nested
    class GetRaw {

        @Test
        void shouldReturnDataFromService() {
            ActuatorRawResponse expected = ActuatorRawResponse.wrap(Map.of("health", "UP"));
            when(actuatorService.getData()).thenReturn(expected);

            ActuatorRawResponse result = controller.getRaw();

            assertThat(result).isSameAs(expected);
        }
    }

    @Nested
    class GetMetrics {

        @Test
        void shouldReturnMetricsFromService() {
            MetricsInfo expected = MetricsInfo.empty();
            when(metricsService.getMetrics()).thenReturn(expected);

            MetricsInfo result = controller.getMetrics();

            assertThat(result).isSameAs(expected);
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
