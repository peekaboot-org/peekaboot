package org.peekaboot.backend.lifecycle.web;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.lifecycle.LifecycleEventsResponse;
import org.peekaboot.backend.domain.lifecycle.LifecycleRunsResponse;
import org.peekaboot.backend.lifecycle.LifecycleEvents;
import org.peekaboot.backend.lifecycle.LifecycleRuns;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PeekabootPaths.BASE_PATH + "/api/lifecycle")
public class LifecycleController {

    private final LifecycleEvents lifecycleEvents;
    private final LifecycleRuns lifecycleRuns;

    public LifecycleController(LifecycleEvents lifecycleEvents, LifecycleRuns lifecycleRuns) {
        this.lifecycleEvents = lifecycleEvents;
        this.lifecycleRuns = lifecycleRuns;
    }

    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleEventsResponse events() {
        return lifecycleEvents.events();
    }

    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleRunsResponse runs() {
        return lifecycleRuns.runs();
    }
}
