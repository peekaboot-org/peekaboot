package org.peekaboot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.peekaboot.backend.config.PeekabootHandlers;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks {@link DashboardAccess} for every request that reached one of Peekaboot's own handlers,
 * whatever path the host's Spring MVC configuration served it at. The guard's servlet filter
 * covers {@code /peekaboot/**} only, and a host can map the same handlers elsewhere.
 */
public final class DashboardAccessInterceptor implements HandlerInterceptor {

    private final DashboardAccess access;

    public DashboardAccessInterceptor(DashboardAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        return !PeekabootHandlers.isPeekaboots(handler) || access.admit(request, response);
    }
}
