package org.peekaboot.backend.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.peekaboot.backend.config.ApiSecurityHeaders;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.problem.ApiProblem;
import org.springframework.http.MediaType;

/** Writes the answer to a request a {@link DashboardAccess} turns away. */
final class Refusals {

    private Refusals() {}

    /**
     * The problem's status and body, set rather than sent: {@code sendError} would dispatch to the
     * error page and re-enter the chain. The refusal comes before the API's header interceptor
     * runs, so it sets {@link ApiSecurityHeaders} itself. An include gets nothing from it. An
     * include cannot set the status, so the including response keeps its own, and a body would
     * land inside the including page. A committed response gets no body either, since its status
     * is already sent and it would only gain trailing bytes.
     */
    static void answer(HttpServletRequest request, HttpServletResponse response, ApiProblem problem)
            throws IOException {
        response.setStatus(problem.status());
        ApiSecurityHeaders.apply(response);
        if (request.getDispatcherType() == DispatcherType.INCLUDE || response.isCommitted()) {
            response.setContentLength(0);
            return;
        }
        byte[] body = PeekabootJson.MAPPER.writeValueAsBytes(problem);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
