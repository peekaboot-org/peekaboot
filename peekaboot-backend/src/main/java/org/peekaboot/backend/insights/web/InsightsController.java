package org.peekaboot.backend.insights.web;

import org.peekaboot.backend.domain.insights.InsightsConfigResponse;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
import org.peekaboot.backend.insights.InsightsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/peekaboot/api/insights")
public class InsightsController {

    private final InsightsService service;
    private final InsightsSsePublisher publisher;

    public InsightsController(InsightsService service, InsightsSsePublisher publisher) {
        this.service = service;
        this.publisher = publisher;
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public InsightsConfigResponse config() {
        return service.config();
    }

    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public LevelDataResponse data(@RequestParam int level) {
        return service.data(level);
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return publisher.subscribe();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
