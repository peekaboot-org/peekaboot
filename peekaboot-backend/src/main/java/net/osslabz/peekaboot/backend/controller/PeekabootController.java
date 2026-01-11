package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.service.ActuatorInsightsService;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.service.TraceInsightsService;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private static final int DEFAULT_TRACE_LIMIT = 100;

    private final PeekabookActuatorService peekabookActuatorService;
    private final ActuatorInsightsService actuatorInsightsService;
    private final TraceInsightsService traceInsightsService;
    private final PeekabootProperties properties;
    private final ObjectProvider<TraceQueryService> traceQueryServiceProvider;
    private final PeekabootTracingProperties tracingProperties;

    public PeekabootController(
            PeekabookActuatorService peekabootService,
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            PeekabootProperties properties,
            ObjectProvider<TraceQueryService> traceQueryServiceProvider,
            ObjectProvider<PeekabootTracingProperties> tracingPropertiesProvider) {
        this.peekabookActuatorService = peekabootService;
        this.actuatorInsightsService = actuatorInsightsService;
        this.traceInsightsService = traceInsightsService;
        this.properties = properties;
        this.traceQueryServiceProvider = traceQueryServiceProvider;
        this.tracingProperties = tracingPropertiesProvider.getIfAvailable();
    }

    @GetMapping(value = "/api/actuator/all/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getRaw() {
        return peekabookActuatorService.getData();
    }

    @GetMapping(value = "/api/actuator/all/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActuatorInsightsResponse getInsights(@RequestParam(required = false) String locale) {
        Locale parsedLocale = (locale != null && !locale.isBlank())
            ? Locale.forLanguageTag(locale.replace('_', '-'))
            : Locale.ENGLISH;
        return actuatorInsightsService.getInsights(parsedLocale);
    }

    @GetMapping(value = "/api/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new HashMap<>();
        features.put("tracing", traceQueryServiceProvider.getIfAvailable() != null);
        features.put("devToolbar", properties.isDevToolbar());
        if (tracingProperties != null) {
            features.put("traceCaptureMode", tracingProperties.getEffectiveCaptureMode(properties.isDevToolbar()).name());
        }
        return features;
    }

    @GetMapping(value = "/api/traces/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TraceData> getTracesRaw(@RequestParam(defaultValue = "100") int limit) {
        TraceQueryService traceQueryService = traceQueryServiceProvider.getIfAvailable();
        if (traceQueryService == null) {
            return List.of();
        }
        return traceQueryService.getTraces(limit, getEffectiveCaptureMode());
    }

    @GetMapping(value = "/api/traces/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceInsightsResponse getTracesInsights(@RequestParam(defaultValue = "100") int limit) {
        return traceInsightsService.getInsights(limit, getEffectiveCaptureMode());
    }

    @GetMapping(value = "/api/traces/{traceId}/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceData> getTraceRaw(@PathVariable String traceId) {
        TraceQueryService traceQueryService = traceQueryServiceProvider.getIfAvailable();
        if (traceQueryService == null) {
            return ResponseEntity.notFound().build();
        }
        return traceQueryService.getTrace(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/api/traces/{traceId}/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceTree> getTraceInsights(@PathVariable String traceId) {
        return traceInsightsService.getTraceInsights(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private TraceCaptureMode getEffectiveCaptureMode() {
        if (tracingProperties == null) {
            return TraceCaptureMode.ALL;
        }
        return tracingProperties.getEffectiveCaptureMode(properties.isDevToolbar());
    }
}
