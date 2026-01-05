package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final PeekabookActuatorService peekabookActuatorService;
    private final Object traceQueryService;

    public PeekabootController(
            PeekabookActuatorService peekabootService,
            @Autowired(required = false) Object traceQueryService) {
        this.peekabookActuatorService = peekabootService;
        this.traceQueryService = traceQueryService;
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
        return Map.of("tracing", traceQueryService != null);
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<?> getErrorTraces(@RequestParam(defaultValue = "50") int limit) {
        if (traceQueryService == null) {
            return Collections.emptyList();
        }
        try {
            var method = traceQueryService.getClass().getMethod("getErrorTraces", int.class);
            return (List<?>) method.invoke(traceQueryService, limit);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}