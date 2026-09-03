package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Marks every {@code /peekaboot/api/**} response as not to be stored and not to be
 * content-sniffed. The API serves environment variables, configuration values and
 * captured request headers; a proxy, CDN or the browser's back-forward cache in front
 * of a staging box has no business keeping any of it. A consumer's Spring Security
 * chain covering the path adds the same headers itself - without one, nothing does.
 */
public class ApiSecurityHeadersInterceptor implements HandlerInterceptor {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
        return true;
    }
}
