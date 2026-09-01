package org.peekaboot.backend.controller;

import java.util.Locale;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final ActuatorInsightsService actuatorInsightsService;
    private final TraceInsightsService traceInsightsService;
    private final MetricsService metricsService;
    private final PeekabootProperties properties;
    private final UiTracingProperties uiTracingProperties;

    /** Absent, like the trace store, while {@code peekaboot.tracing.enabled} is false. */
    @Nullable
    private final PeekabootTracingProperties tracingProperties;

    @Nullable
    private final InsightsService insightsService;

    public PeekabootController(
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            MetricsService metricsService,
            PeekabootProperties properties,
            UiTracingProperties uiTracingProperties,
            @Nullable PeekabootTracingProperties tracingProperties,
            @Nullable InsightsService insightsService) {
        this.actuatorInsightsService = actuatorInsightsService;
        this.traceInsightsService = traceInsightsService;
        this.metricsService = metricsService;
        this.properties = properties;
        this.uiTracingProperties = uiTracingProperties;
        this.tracingProperties = tracingProperties;
        this.insightsService = insightsService;
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
                metricsService.isAvailable(),
                properties.isDevToolbar(),
                properties.isEnableUnmasking(),
                insightsService != null,
                uiTracingProperties.getSlowSpanThresholdMs(),
                uiTracingProperties.getVerySlowSpanThresholdMs(),
                uiTracingProperties.getSlowQueryThresholdMs(),
                tracingProperties != null ? tracingProperties.getSlowTraceThresholdMs() : null,
                MaskingEngine.MASK_LITERAL);
    }

    /**
     * The single place the two independent unmasking opt-ins are combined: the
     * server-side {@code peekaboot.enable-unmasking} property and the request's
     * {@code unmask} parameter. Neither is sufficient alone - while the property is
     * false, {@code requestedUnmask} is ignored, so the request can never be a bypass
     * on its own. Every endpoint that carries property values calls this exactly once
     * and threads the result down, rather than re-deriving the decision per mapper.
     */
    private boolean resolveUnmask(boolean requestedUnmask) {
        return properties.isEnableUnmasking() && requestedUnmask;
    }

    @GetMapping(value = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetricsInfo getMetrics() {
        return metricsService.getMetrics();
    }

    @GetMapping(value = "/api/traces/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceInsightsResponse getTracesInsights(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "rootActionType", required = false) String rootActionType,
            @RequestParam(name = "rootOperation", required = false) String rootOperation) {
        return traceInsightsService.getInsights(
                sanitizeLimit(limit), parseBucket(bucket), rootActionType, rootOperation);
    }

    /** Lenient: null, blank and unknown values all mean the All bucket, whatever the case. */
    private static TraceBucket parseBucket(String value) {
        if (value == null || value.isBlank()) {
            return TraceBucket.ALL;
        }
        try {
            return TraceBucket.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TraceBucket.ALL;
        }
    }

    /**
     * Negative limits would throw from Stream.limit; excessive ones overflow
     * downstream arithmetic.
     */
    private int sanitizeLimit(int limit) {
        return Math.clamp(limit, 0, 10_000);
    }

    @GetMapping(value = "/api/traces/{traceId}/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceTree> getTraceInsights(@PathVariable(name = "traceId") String traceId) {
        return traceInsightsService
                .getTraceInsights(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
