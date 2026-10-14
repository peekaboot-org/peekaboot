package org.peekaboot.fixtures;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * An application path that includes the dashboard into its own response. An include cannot set
 * a status, so whatever the dashboard writes lands between {@link #BEFORE} and {@link #AFTER}.
 */
@Controller
public class DashboardIncludeController {

    public static final String PATH = "/fixtures/include-dashboard";
    public static final String BEFORE = "before-include|";
    public static final String AFTER = "|after-include";

    /** The output stream, not the writer: the dashboard's message converters write to the stream. */
    @GetMapping(PATH)
    public void includeDashboard(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/plain");
        ServletOutputStream out = response.getOutputStream();
        out.write(BEFORE.getBytes(StandardCharsets.UTF_8));
        request.getRequestDispatcher("/peekaboot/api/actuator/all/insights").include(request, response);
        out.write(AFTER.getBytes(StandardCharsets.UTF_8));
    }
}
