package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceListSummary;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PeekabootControllerTest {

    private ActuatorInsightsService actuatorInsightsService;
    private TraceInsightsService traceInsightsService;
    private MetricsService metricsService;
    private PeekabootProperties properties;

    private PeekabootController controller;

    @BeforeEach
    void setUp() {
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        traceInsightsService = mock(TraceInsightsService.class);
        metricsService = mock(MetricsService.class);
        properties = new PeekabootProperties();

        when(metricsService.isAvailable()).thenReturn(true);

        controller = new PeekabootController(actuatorInsightsService, traceInsightsService, metricsService, properties);
    }

    @Nested
    class RemovedRawEndpoints {

        /**
         * The /raw endpoints had no caller in the dashboard or the toolbar and were the
         * broadest surface Peekaboot exposed. They are gone, not deprecated - nothing is
         * released, so there is no consumer to keep working.
         */
        @Test
        void shouldNoLongerExposeAnyRawEndpoint() {
            assertThat(PeekabootController.class.getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .doesNotContain("getRaw", "getTracesRaw", "getTraceRaw");
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
            return new TraceInsightsResponse(List.of(), new TraceListSummary(0, 0, 0, 0.0), BucketCounts.empty());
        }
    }

    @Nested
    class GetTraceInsights {

        @Test
        void shouldReturnTraceTreeWhenFound() {
            TraceTree traceTree = createTraceTree("trace-456");
            when(traceInsightsService.getTraceInsights("trace-456")).thenReturn(Optional.of(traceTree));

            ResponseEntity<TraceTree> result = controller.getTraceInsights("trace-456");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().traceId()).isEqualTo("trace-456");
        }

        @Test
        void shouldReturn404WhenTraceNotFound() {
            when(traceInsightsService.getTraceInsights("unknown")).thenReturn(Optional.empty());

            ResponseEntity<TraceTree> result = controller.getTraceInsights("unknown");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class GetFeatures {

        @Test
        void shouldIncludeTracingFeatureWhenTracingAvailable() {
            when(traceInsightsService.isTracingAvailable()).thenReturn(true);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("tracing")).isEqualTo(true);
        }

        @Test
        void shouldIncludeTracingFeatureAsFalseWhenTracingUnavailable() {
            // tracing disabled: the service bean exists but has no TraceStore
            when(traceInsightsService.isTracingAvailable()).thenReturn(false);

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

        @Test
        void shouldReportUnmaskingDisabledByDefault() {
            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("unmaskingEnabled")).isEqualTo(false);
        }

        @Test
        void shouldReportUnmaskingEnabledWhenThePropertyIsSet() {
            properties.setEnableUnmasking(true);

            Map<String, Object> features = controller.getFeatures();

            assertThat(features.get("unmaskingEnabled")).isEqualTo(true);
        }
    }

    @Nested
    class GetInsights {

        @Test
        void shouldDefaultToEnglishWhenLocaleIsNull() {
            controller.getInsights(null, false);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, false);
        }

        @Test
        void shouldDefaultToEnglishWhenLocaleIsBlank() {
            controller.getInsights("  ", false);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, false);
        }

        @Test
        void shouldReplaceUnderscoreWithHyphenBeforeParsingLocale() {
            controller.getInsights("en_US", false);

            verify(actuatorInsightsService).getInsights(Locale.forLanguageTag("en-US"), false);
        }

        @Test
        void shouldParseLocaleWithHyphenDirectly() {
            controller.getInsights("de-DE", false);

            verify(actuatorInsightsService).getInsights(Locale.forLanguageTag("de-DE"), false);
        }

        @Test
        void shouldReturnResponseFromService() {
            ActuatorInsightsResponse expected =
                    new ActuatorInsightsResponse(null, null, null, null, null, null, null, null, null, null);
            when(actuatorInsightsService.getInsights(any(), anyBoolean())).thenReturn(expected);

            ActuatorInsightsResponse result = controller.getInsights(null, false);

            assertThat(result).isSameAs(expected);
        }

        /**
         * The single most important test in the unmasking feature: the request's
         * unmask=true parameter must never be a bypass on its own. properties starts
         * with enableUnmasking false (PeekabootProperties' own default), so this pins
         * that the controller still resolves to masked in that state regardless of
         * what the request asks for.
         */
        @Test
        void shouldIgnoreUnmaskTrueWhenUnmaskingIsDisabled() {
            controller.getInsights(null, true);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, false);
        }

        @Test
        void shouldRequestUnmaskedDataOnlyWhenBothThePropertyAndTheParameterAreTrue() {
            properties.setEnableUnmasking(true);

            controller.getInsights(null, true);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, true);
        }

        @Test
        void shouldStayMaskedWhenUnmaskingIsEnabledButTheParameterIsNotSet() {
            properties.setEnableUnmasking(true);

            controller.getInsights(null, false);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, false);
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
                List.of());

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
                        new TraceTabSummary.LogsSummary(0, 0, 0)),
                Map.of(),
                null,
                null,
                null,
                false);
    }
}
