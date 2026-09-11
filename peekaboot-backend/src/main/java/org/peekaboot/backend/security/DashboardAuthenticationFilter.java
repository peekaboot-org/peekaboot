package org.peekaboot.backend.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Challenges any {@code /peekaboot/**} request that reaches it unauthenticated.
 *
 * <p>Registered after Spring Security's own filter, so it is nested inside the security chain
 * and sees only requests that chain already let through. A request the application authenticated
 * passes through untouched; one it permitted anonymously is challenged with Peekaboot's own
 * credentials.
 */
public class DashboardAuthenticationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DashboardAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CHALLENGE_HEADER = "WWW-Authenticate";
    private static final String CHALLENGE = "Basic realm=\"Peekaboot\", charset=\"UTF-8\"";
    private static final String BASIC_PREFIX = "basic ";

    private final DashboardCredentials credentials;
    private final CredentialCache cache;
    private final RequestAuthentication requestAuthentication;

    /** Guards the one-time WARN: the moment Peekaboot learns nothing else protects the dashboard. */
    private final AtomicBoolean challengeLogged = new AtomicBoolean();

    public DashboardAuthenticationFilter(
            DashboardCredentials credentials, CredentialCache cache, RequestAuthentication requestAuthentication) {
        this.credentials = credentials;
        this.cache = cache;
        this.requestAuthentication = requestAuthentication;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        if (requestAuthentication.alreadyAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String header = httpRequest.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !verify(header)) {
            challenge(httpRequest, httpResponse);
            return;
        }

        requestAuthentication.record(credentials.username());
        try {
            chain.doFilter(request, response);
        } finally {
            requestAuthentication.clear();
        }
    }

    private boolean verify(String header) {
        if (cache.isKnownGood(header)) {
            return true;
        }
        if (!header.toLowerCase(Locale.ROOT).startsWith(BASIC_PREFIX)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(
                    Base64.getDecoder()
                            .decode(header.substring(BASIC_PREFIX.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            return false;
        }
        String username = decoded.substring(0, separator);
        String password = decoded.substring(separator + 1);
        if (!constantTimeEquals(credentials.username(), username)
                || !credentials.passwordHash().matches(password)) {
            return false;
        }
        cache.remember(header);
        return true;
    }

    /** A bare 401 rather than {@code sendError}, which would dispatch to the error page and re-enter the chain. */
    private void challenge(HttpServletRequest request, HttpServletResponse response) {
        if (challengeLogged.compareAndSet(false, true)) {
            log.warn(
                    "Challenged an unauthenticated request for {} - nothing but Peekaboot's own generated"
                            + " credentials is protecting the dashboard. See"
                            + " https://www.peekaboot.org/docs/security/",
                    request.getRequestURI());
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(CHALLENGE_HEADER, CHALLENGE);
        response.setContentLength(0);
    }

    /** Username comparison need not be secret, but a variable-time compare here would be an odd asymmetry. */
    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
