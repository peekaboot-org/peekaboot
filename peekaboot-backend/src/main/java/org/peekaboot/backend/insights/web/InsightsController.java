package org.peekaboot.backend.insights.web;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.insights.InsightsConfigResponse;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.LevelDataResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(PeekabootPaths.BASE_PATH + "/api/insights")
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

    /** {@code level} is optional here so that its refusal can name the accepted range. */
    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public LevelDataResponse data(@RequestParam(name = "level", required = false) @Nullable Integer level) {
        return service.data(level);
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return publisher.subscribe();
    }
}
