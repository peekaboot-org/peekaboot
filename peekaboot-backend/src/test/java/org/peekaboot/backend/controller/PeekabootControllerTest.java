package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;

class PeekabootControllerTest {

    private static final Features FEATURES =
            new Features(true, false, true, false, true, false, 250, 900, 75, MaskingEngine.MASK_LITERAL);

    private ActuatorInsightsService actuatorInsightsService;
    private MetricsService metricsService;

    private PeekabootController controller;

    @BeforeEach
    void setUp() {
        actuatorInsightsService = mock(ActuatorInsightsService.class);
        metricsService = mock(MetricsService.class);

        controller = new PeekabootController(actuatorInsightsService, metricsService, FEATURES);
    }

    @Nested
    class GetFeatures {

        @Test
        void shouldReturnTheFeaturesItWasBuiltWith() {
            assertThat(controller.getFeatures()).isSameAs(FEATURES);
        }
    }

    @Nested
    class GetInsights {

        @Test
        void shouldReturnResponseFromService() {
            ActuatorInsightsResponse expected =
                    new ActuatorInsightsResponse(null, null, null, null, null, null, null, null, null, null, null);
            when(actuatorInsightsService.insightsFor("de-DE", true)).thenReturn(expected);

            ActuatorInsightsResponse result = controller.getInsights("de-DE", true);

            assertThat(result).isSameAs(expected);
        }
    }

    @Nested
    class GetMetrics {

        @Test
        void shouldReturnMetricsFromService() {
            MetricsInfo expected = MetricsInfo.empty();
            when(metricsService.getMetrics("jvm.")).thenReturn(expected);

            MetricsInfo result = controller.getMetrics("jvm.");

            assertThat(result).isSameAs(expected);
        }
    }
}
