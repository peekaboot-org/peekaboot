package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final PeekabookActuatorService peekabookActuatorService;
    private final boolean tracingEnabled;

    public PeekabootController(
            PeekabookActuatorService peekabootService,
            ApplicationContext applicationContext) {
        this.peekabookActuatorService = peekabootService;
        this.tracingEnabled = applicationContext.containsBean("tracingController");
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
    public Map<String, Boolean> getFeatures() {
        return Map.of("tracing", tracingEnabled);
    }
}