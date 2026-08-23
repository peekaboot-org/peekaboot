package org.peekaboot.backend.controller;

import org.peekaboot.backend.actuator.raw.ActuatorRawResponse;
import org.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.domain.trace.TraceInsightsResponse;
import org.peekaboot.backend.domain.trace.TraceRawData;
import org.peekaboot.backend.domain.trace.TraceRawResponse;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.service.TraceRawService;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final PeekabootActuatorService peekabootActuatorService;
    private final ActuatorInsightsService actuatorInsightsService;
    private final TraceInsightsService traceInsightsService;
    private final TraceRawService traceRawService;
    private final MetricsService metricsService;
    private final PeekabootProperties properties;

    public PeekabootController(
            PeekabootActuatorService peekabootService,
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            TraceRawService traceRawService,
            MetricsService metricsService,
            PeekabootProperties properties) {
        this.peekabootActuatorService = peekabootService;
        this.actuatorInsightsService = actuatorInsightsService;
        this.traceInsightsService = traceInsightsService;
        this.traceRawService = traceRawService;
        this.metricsService = metricsService;
        this.properties = properties;
    }

    @GetMapping(value = "/api/actuator/all/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActuatorRawResponse getRaw(@RequestParam(name = "unmask", defaultValue = "false") boolean unmask) {
        return peekabootActuatorService.getData(resolveUnmask(unmask));
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
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new HashMap<>();
        features.put("tracing", traceRawService.isTracingAvailable());
        features.put("metrics", metricsService.isAvailable());
        features.put("devToolbar", properties.isDevToolbar());
        features.put("unmaskingEnabled", properties.isEnableUnmasking());
        return features;
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

    @GetMapping(value = "/api/traces/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceRawResponse getTracesRaw(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "bucket", required = false) String bucket) {
        return traceRawService.getTraces(sanitizeLimit(limit), TraceBucket.fromParam(bucket));
    }

    @GetMapping(value = "/api/traces/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceInsightsResponse getTracesInsights(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "rootActionType", required = false) String rootActionType,
            @RequestParam(name = "rootOperation", required = false) String rootOperation) {
        return traceInsightsService.getInsights(sanitizeLimit(limit), TraceBucket.fromParam(bucket), rootActionType, rootOperation);
    }

    /**
     * Negative limits would throw from Stream.limit; excessive ones overflow
     * downstream arithmetic.
     */
    private int sanitizeLimit(int limit) {
        return Math.clamp(limit, 0, 10_000);
    }

    @GetMapping(value = "/api/traces/{traceId}/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceRawData> getTraceRaw(@PathVariable(name = "traceId") String traceId) {
        return traceRawService.getTrace(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/api/traces/{traceId}/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceTree> getTraceInsights(@PathVariable(name = "traceId") String traceId) {
        return traceInsightsService.getTraceInsights(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}