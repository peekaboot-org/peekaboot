package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.actuator.raw.ActuatorRawResponse;
import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.domain.metrics.MetricsInfo;
import net.osslabz.peekaboot.backend.domain.trace.TraceInsightsResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawData;
import net.osslabz.peekaboot.backend.domain.trace.TraceRawResponse;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.service.ActuatorInsightsService;
import net.osslabz.peekaboot.backend.service.MetricsService;
import net.osslabz.peekaboot.backend.service.PeekabootActuatorService;
import net.osslabz.peekaboot.backend.service.TraceInsightsService;
import net.osslabz.peekaboot.backend.service.TraceRawService;
import net.osslabz.peekaboot.backend.tracing.store.TraceBucket;
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
    public ActuatorRawResponse getRaw() {
        return peekabootActuatorService.getData();
    }

    @GetMapping(value = "/api/actuator/all/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActuatorInsightsResponse getInsights(@RequestParam(name = "locale", required = false) String locale) {
        Locale parsedLocale = (locale != null && !locale.isBlank())
            ? Locale.forLanguageTag(locale.replace('_', '-'))
            : Locale.ENGLISH;
        return actuatorInsightsService.getInsights(parsedLocale);
    }

    @GetMapping(value = "/api/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new HashMap<>();
        features.put("tracing", traceRawService.isTracingAvailable());
        features.put("metrics", metricsService.isAvailable());
        features.put("devToolbar", properties.isDevToolbar());
        return features;
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