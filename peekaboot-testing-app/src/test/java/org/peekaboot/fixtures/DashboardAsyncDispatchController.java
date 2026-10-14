package org.peekaboot.fixtures;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** An application path that hands its request to the dashboard through an ASYNC dispatch. */
@Controller
public class DashboardAsyncDispatchController {

    public static final String PATH = "/fixtures/async-dispatch-to-dashboard";

    /** The response parameter tells Spring the handler deals with the response, so it resolves no view. */
    @GetMapping(PATH)
    public void dispatchToDashboard(HttpServletRequest request, HttpServletResponse response) {
        request.startAsync(request, response).dispatch("/peekaboot/api/actuator/all/insights");
    }
}
