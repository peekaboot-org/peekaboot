package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sets {@link ApiSecurityHeaders} on every {@code /peekaboot/api/**} response. A consumer's
 * Spring Security chain covering the path adds the same headers itself; without one, nothing
 * does.
 */
class ApiSecurityHeadersInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ApiSecurityHeaders.apply(response);
        return true;
    }
}
