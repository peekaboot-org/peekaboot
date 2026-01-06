package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/peekaboot")
@ConditionalOnBean(TraceQueryService.class)
public class TracingController {

    private final TraceQueryService traceQueryService;
    private final TraceCaptureMode captureMode;

    public TracingController(TraceQueryService traceQueryService, PeekabootTracingProperties properties) {
        this.traceQueryService = traceQueryService;
        this.captureMode = properties.getEffectiveCaptureMode();
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TraceData> getTraces(@RequestParam(defaultValue = "50") int limit) {
        return traceQueryService.getTraces(limit, captureMode);
    }
}
