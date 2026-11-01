package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;

class PeekabootControllerTest {

    private ActuatorInsightsService actuatorInsightsService;
    private TraceInsightsService traceInsightsService;
    private MetricsService metricsService;
    private PeekabootProperties properties;
    private PeekabootTracingProperties tracingProperties;

    private PeekabootController controller;

    @BeforeEach
    void setUp() {
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        traceInsightsService = mock(TraceInsightsService.class);
        metricsService = mock(MetricsService.class);
        properties = new PeekabootProperties();
        tracingProperties = new PeekabootTracingProperties();

        when(metricsService.isAvailable()).thenReturn(true);

        controller = controller(null, false, false);
    }

    @Nested
    class GetFeatures {

        @Test
        void shouldIncludeTracingFeatureWhenTracingAvailable() {
            when(traceInsightsService.isTracingAvailable()).thenReturn(true);

            assertThat(controller.getFeatures().tracing()).isTrue();
        }

        @Test
        void shouldIncludeTracingFeatureAsFalseWhenTracingUnavailable() {
            // tracing disabled: the service bean exists but has no TraceStore
            when(traceInsightsService.isTracingAvailable()).thenReturn(false);

            assertThat(controller.getFeatures().tracing()).isFalse();
        }

        @Test
        void shouldIncludeMetricsFeatureAsFalseWhenUnavailable() {
            when(metricsService.isAvailable()).thenReturn(false);

            assertThat(controller.getFeatures().metrics()).isFalse();
        }

        @Test
        void shouldReportUnmaskingDisabledByDefault() {
            assertThat(controller.getFeatures().unmaskingEnabled()).isFalse();
        }

        @Test
        void shouldReportUnmaskingEnabledWhenThePropertyIsSet() {
            properties.getUnmasking().setEnabled(true);

            assertThat(controller.getFeatures().unmaskingEnabled()).isTrue();
        }

        @Test
        void shouldIncludeInsightsFeatureAsFalseWhenInsightsServiceAbsent() {
            assertThat(controller.getFeatures().insights()).isFalse();
        }

        @Test
        void shouldIncludeInsightsFeatureAsTrueWhenInsightsServicePresent() {
            PeekabootController controllerWithInsights = controller(mock(InsightsService.class), false, false);

            assertThat(controllerWithInsights.getFeatures().insights()).isTrue();
        }

        /** The frontend colours durations by the same numbers IssueDetector raises issues at. */
        @Test
        void shouldPublishTheEffectiveSpanAndQueryThresholds() {
            tracingProperties.setSlowSpanThresholdMs(250);
            tracingProperties.setVerySlowSpanThresholdMs(900);
            tracingProperties.setSlowQueryThresholdMs(75);

            Features features = controller.getFeatures();

            assertThat(features.slowSpanThresholdMs()).isEqualTo(250L);
            assertThat(features.verySlowSpanThresholdMs()).isEqualTo(900L);
            assertThat(features.slowQueryThresholdMs()).isEqualTo(75L);
        }

        /** The Request tab highlights masked headers by comparing against this exact literal. */
        @Test
        void shouldPublishTheMaskLiteral() {
            assertThat(controller.getFeatures().maskLiteral()).isEqualTo(MaskingEngine.MASK_LITERAL);
        }

        @Test
        void shouldReportTracingSpansPossibleWhenASpanSourceIsWired() {
            PeekabootController withSpanSource = controller(null, true, false);

            assertThat(withSpanSource.getFeatures().tracingSpansPossible()).isTrue();
        }

        @Test
        void shouldReportLifecycleWhenItsRunHistoryIsServed() {
            assertThat(controller(null, false, true).getFeatures().lifecycle()).isTrue();
        }

        @Test
        void shouldReportLifecycleOffWithoutItsRunHistory() {
            assertThat(controller.getFeatures().lifecycle()).isFalse();
        }

        @Test
        void shouldReportTracingSpansNotPossibleWithoutASpanSource() {
            assertThat(controller.getFeatures().tracingSpansPossible()).isFalse();
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
                    new ActuatorInsightsResponse(null, null, null, null, null, null, null, null, null, null, null);
            when(actuatorInsightsService.getInsights(any(), anyBoolean())).thenReturn(expected);

            ActuatorInsightsResponse result = controller.getInsights(null, false);

            assertThat(result).isSameAs(expected);
        }

        /**
         * The single most important test in the unmasking feature: the request's
         * unmask=true parameter must never be a bypass on its own. properties starts
         * with unmasking.enabled false (PeekabootProperties' own default), so this pins
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
            properties.getUnmasking().setEnabled(true);

            controller.getInsights(null, true);

            verify(actuatorInsightsService).getInsights(Locale.ENGLISH, true);
        }

        @Test
        void shouldStayMaskedWhenUnmaskingIsEnabledButTheParameterIsNotSet() {
            properties.getUnmasking().setEnabled(true);

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

    /** The controller over the test's fields, with the three constructor arguments the feature tests vary. */
    private PeekabootController controller(InsightsService insights, boolean spanSourceWired, boolean lifecycle) {
        return new PeekabootController(
                actuatorInsightsService,
                traceInsightsService,
                metricsService,
                properties,
                tracingProperties,
                insights,
                spanSourceWired,
                lifecycle);
    }
}
