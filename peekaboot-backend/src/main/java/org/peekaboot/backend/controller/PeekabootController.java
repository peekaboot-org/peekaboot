package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
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
    private final MetricsService metricsService;
    private final Features features;

    public PeekabootController(
            ActuatorInsightsService actuatorInsightsService, MetricsService metricsService, Features features) {
        this.actuatorInsightsService = actuatorInsightsService;
        this.metricsService = metricsService;
        this.features = features;
    }

    @GetMapping(value = "/api/actuator/all/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActuatorInsightsResponse getInsights(
            @RequestParam(name = "locale", required = false) String locale,
            @RequestParam(name = "unmask", defaultValue = "false") boolean unmask) {
        return actuatorInsightsService.insightsFor(locale, unmask);
    }

    @GetMapping(value = "/api/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public Features getFeatures() {
        return features;
    }

    @GetMapping(value = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetricsInfo getMetrics(@RequestParam(name = "name", required = false) String name) {
        return metricsService.getMetrics(name);
    }
}
