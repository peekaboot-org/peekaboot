package org.peekaboot.fixtures;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** An application path that forwards into the dashboard, the route a guard on REQUEST dispatches alone lets through. */
@Controller
public class DashboardForwardController {

    public static final String PATH = "/fixtures/forward-to-dashboard";

    @GetMapping(PATH)
    public String forwardToDashboard() {
        return "forward:/peekaboot/api/actuator/all/insights";
    }
}
