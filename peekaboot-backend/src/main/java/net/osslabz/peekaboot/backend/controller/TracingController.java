package net.osslabz.peekaboot.backend.controller;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.log.LogEntry;
import net.osslabz.peekaboot.backend.log.TraceLogStore;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/peekaboot")
@ConditionalOnBean(TraceQueryService.class)
public class TracingController {

    private final TraceQueryService traceQueryService;
    private final TraceCaptureMode captureMode;
    private final TraceLogStore traceLogStore;

    public TracingController(
            TraceQueryService traceQueryService,
            PeekabootTracingProperties tracingProperties,
            PeekabootProperties properties,
            @Autowired(required = false) TraceLogStore traceLogStore) {
        this.traceQueryService = traceQueryService;
        this.captureMode = tracingProperties.getEffectiveCaptureMode(properties.isDevToolbar());
        this.traceLogStore = traceLogStore;
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

    @GetMapping(value = "/api/traces/{traceId}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTraceDetails(@PathVariable(name = "traceId") String traceId) {
        Optional<TraceData> traceOpt = traceQueryService.getTrace(traceId);
        if (traceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TraceData trace = traceOpt.get();
        Map<String, Object> details = new HashMap<>();
        details.put("traceId", trace.traceId());
        details.put("startTime", formatInstant(trace.startTime()));
        details.put("endTime", formatInstant(trace.endTime()));
        details.put("duration", trace.duration());
        details.put("spanCount", trace.spanCount());
        details.put("spans", trace.spans().stream().map(this::spanToMap).toList());

        if (traceLogStore != null) {
            List<LogEntry> logs = traceLogStore.getLogsForTrace(traceId);
            details.put("logs", logs.stream().map(this::logToMap).toList());
        } else {
            details.put("logs", List.of());
        }

        return ResponseEntity.ok(details);
    }

    private Map<String, Object> spanToMap(SpanData span) {
        Map<String, Object> map = new HashMap<>();
        map.put("traceId", span.traceId());
        map.put("spanId", span.spanId());
        map.put("parentId", span.parentId());
        map.put("name", span.name());
        map.put("kind", span.kind() != null ? span.kind().name() : null);
        map.put("startTime", formatInstant(span.startTime()));
        map.put("endTime", formatInstant(span.endTime()));
        map.put("duration", span.duration());
        map.put("tags", span.tags());
        map.put("events", span.events());
        map.put("errorMessage", span.errorMessage());
        map.put("errorClass", span.errorClass());
        map.put("remoteServiceName", span.remoteServiceName());
        map.put("remoteIp", span.remoteIp());
        map.put("remotePort", span.remotePort());
        return map;
    }

    private Map<String, Object> logToMap(LogEntry log) {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", formatInstant(log.timestamp()));
        map.put("level", log.level());
        map.put("loggerName", log.loggerName());
        map.put("message", log.message());
        map.put("threadName", log.threadName());
        return map;
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
