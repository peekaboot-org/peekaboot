package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final PeekabookActuatorService peekabookActuatorService;
    private final PeekabootProperties properties;
    private final ObjectProvider<TraceQueryService> traceQueryServiceProvider;
    private final PeekabootTracingProperties tracingProperties;

    public PeekabootController(
            PeekabookActuatorService peekabootService,
            PeekabootProperties properties,
            ObjectProvider<TraceQueryService> traceQueryServiceProvider,
            ObjectProvider<PeekabootTracingProperties> tracingPropertiesProvider) {
        this.peekabookActuatorService = peekabootService;
        this.properties = properties;
        this.traceQueryServiceProvider = traceQueryServiceProvider;
        this.tracingProperties = tracingPropertiesProvider.getIfAvailable();
    }

    @GetMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getData() {
        return peekabookActuatorService.getData();
    }

    @GetMapping(value = "/api/v2", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getDataGeneric() {
        return peekabookActuatorService.getData();
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
}