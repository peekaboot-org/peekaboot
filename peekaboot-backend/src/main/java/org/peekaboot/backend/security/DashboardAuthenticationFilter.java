package org.peekaboot.backend.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Challenges every {@code /peekaboot/**} request that does not carry Peekaboot's own credentials.
 *
 * <p>Whatever else authenticated the request does not count. An application that secures the
 * dashboard itself switches this guard off with {@code peekaboot.security.enabled=false}.
 *
 * <p>An application that wants its own guard instead overrides this by declaring a
 * {@code FilterRegistrationBean<DashboardAuthenticationFilter>} bean of its own - the type
 * {@code @ConditionalOnMissingBean} matches on Peekaboot's registration. A bare
 * {@code DashboardAuthenticationFilter} bean does not back it off and is auto-registered by Boot
 * at {@code /*} on top of it, putting HTTP Basic in front of the whole application instead of
 * standing the guard down.
 */
public class DashboardAuthenticationFilter implements Filter {

    private final DashboardAccess access;

    public DashboardAuthenticationFilter(DashboardCredentials credentials, CredentialCache cache) {
        this.access = new CredentialsAccess(credentials, cache);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            // Not HTTP means no 401 can be sent; forwarding would serve the dashboard unguarded,
            // so refuse instead of failing open.
            throw new ServletException("DashboardAuthenticationFilter requires an HTTP request");
        }

        if (access.admit(httpRequest, httpResponse)) {
            chain.doFilter(request, response);
        }
    }

    /** What this filter asks, for the handler-level check to ask the same. */
    public DashboardAccess access() {
        return access;
    }
}
