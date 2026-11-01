package org.peekaboot.backend.controller;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootPaths;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard's actuator data, meters and feature flags. */
@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class PeekabootController {

    private final ActuatorInsightsService actuatorInsightsService;
    private final TraceInsightsService traceInsightsService;
    private final MetricsService metricsService;
    private final PeekabootProperties properties;
    private final PeekabootTracingProperties tracingProperties;

    @Nullable
    private final InsightsService insightsService;

    /** Whether a span source is wired to the trace store - see {@link Features#tracingSpansPossible}. */
    private final boolean tracingSpansPossible;

    /** See {@link Features#lifecycle}. */
    private final boolean lifecycle;

    public PeekabootController(
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            MetricsService metricsService,
            PeekabootProperties properties,
            PeekabootTracingProperties tracingProperties,
            @Nullable InsightsService insightsService,
            boolean tracingSpansPossible,
            boolean lifecycle) {
        this.actuatorInsightsService = actuatorInsightsService;
        this.traceInsightsService = traceInsightsService;
        this.metricsService = metricsService;
        this.properties = properties;
        this.tracingProperties = tracingProperties;
        this.insightsService = insightsService;
        this.tracingSpansPossible = tracingSpansPossible;
        this.lifecycle = lifecycle;
    }

    @GetMapping(value = "/api/actuator/all/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActuatorInsightsResponse getInsights(
            @RequestParam(name = "locale", required = false) String locale,
            @RequestParam(name = "unmask", defaultValue = "false") boolean unmask) {
        Locale parsedLocale = (locale != null && !locale.isBlank())
                ? Locale.forLanguageTag(locale.replace('_', '-'))
                : Locale.ENGLISH;
        return actuatorInsightsService.getInsights(parsedLocale, resolveUnmask(unmask));
    }

    @GetMapping(value = "/api/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public Features getFeatures() {
        return new Features(
                traceInsightsService.isTracingAvailable(),
                tracingSpansPossible,
                metricsService.isAvailable(),
                properties.getUnmasking().isEnabled(),
                insightsService != null,
                lifecycle,
                tracingProperties.getSlowSpanThresholdMs(),
                tracingProperties.getVerySlowSpanThresholdMs(),
                tracingProperties.getSlowQueryThresholdMs(),
                MaskingEngine.MASK_LITERAL);
    }

    /**
     * The single place the two independent unmasking opt-ins are combined: the
     * server-side {@code peekaboot.unmasking.enabled} property and the request's
     * {@code unmask} parameter. Neither is sufficient alone - while the property is
     * false, {@code requestedUnmask} is ignored, so the request can never be a bypass
     * on its own. Every endpoint that carries property values calls this exactly once
     * and threads the result down, rather than re-deriving the decision per mapper.
     */
    private boolean resolveUnmask(boolean requestedUnmask) {
        return properties.getUnmasking().isEnabled() && requestedUnmask;
    }

    @GetMapping(value = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetricsInfo getMetrics() {
        return metricsService.getMetrics();
    }
}
