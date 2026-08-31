package org.peekaboot.backend.lifecycle.web;

import org.peekaboot.backend.domain.lifecycle.LifecycleEventsResponse;
import org.peekaboot.backend.lifecycle.LifecycleEvents;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/peekaboot/api/lifecycle")
public class LifecycleController {

    private final LifecycleEvents lifecycleEvents;

    public LifecycleController(LifecycleEvents lifecycleEvents) {
        this.lifecycleEvents = lifecycleEvents;
    }

    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleEventsResponse events() {
        return lifecycleEvents.events();
    }
}
