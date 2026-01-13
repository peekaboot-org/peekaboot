package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/peekaboot")
@ConditionalOnBean(TraceQueryService.class)
public class TracingController {

    private final TraceQueryService traceQueryService;
    private final TraceCaptureMode captureMode;

    public TracingController(
            TraceQueryService traceQueryService,
            PeekabootTracingProperties tracingProperties,
            PeekabootProperties properties) {
        this.traceQueryService = traceQueryService;
        this.captureMode = tracingProperties.getEffectiveCaptureMode(properties.isDevToolbar());
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TraceData> getTraces(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return traceQueryService.getTraces(limit, captureMode);
    }

    @GetMapping(value = "/api/traces/{traceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TraceData> getTrace(@PathVariable(name = "traceId") String traceId) {
        Optional<TraceData> trace = traceQueryService.getTrace(traceId);
        return trace.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
