package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
    private final boolean tracingEnabled;
    private final PeekabootTracingProperties tracingProperties;

    public PeekabootController(
            PeekabookActuatorService peekabootService,
            ApplicationContext applicationContext,
            @Autowired(required = false) PeekabootTracingProperties tracingProperties) {
        this.peekabookActuatorService = peekabootService;
        this.tracingEnabled = applicationContext.containsBean("tracingController");
        this.tracingProperties = tracingProperties;
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
        features.put("tracing", tracingEnabled);
        if (tracingProperties != null) {
            features.put("traceCaptureMode", tracingProperties.getEffectiveCaptureMode().name());
            features.put("debugToolbar", tracingProperties.isDebugToolbar());
        }
        return features;
    }
}