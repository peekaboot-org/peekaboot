package net.osslabz.peekaboot.backend.controller;

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

    public TracingController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TraceData> getErrorTraces(@RequestParam(defaultValue = "50") int limit) {
        return traceQueryService.getErrorTraces(limit);
    }
}
