package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tells the browser what Peekaboot's UI may load, that no page may frame it and that its files
 * are what their content type says. The UI has no inline script or style and loads nothing from
 * another origin, which is what lets the policy name {@code 'self'} alone.
 *
 * <p>A header the response already carries is the host's and stays.
 */
class UiSecurityHeadersInterceptor implements HandlerInterceptor {

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String X_FRAME_OPTIONS = "X-Frame-Options";
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private static final String UI_POLICY =
            "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        setUnlessPresent(response, CONTENT_SECURITY_POLICY, UI_POLICY);
        setUnlessPresent(response, X_FRAME_OPTIONS, "DENY");
        setUnlessPresent(response, X_CONTENT_TYPE_OPTIONS, "nosniff");
        return true;
    }

    private static void setUnlessPresent(HttpServletResponse response, String name, String value) {
        if (!response.containsHeader(name)) {
            response.setHeader(name, value);
        }
    }
}
