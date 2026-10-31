package org.peekaboot.backend.security;

import jakarta.servlet.DispatcherType;
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
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.ApiSecurityHeaders;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.problem.ApiProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

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

    private static final Logger log = LoggerFactory.getLogger(DashboardAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CHALLENGE_HEADER = "WWW-Authenticate";
    private static final String CHALLENGE = "Basic realm=\"Peekaboot\", charset=\"UTF-8\"";
    private static final String BASIC_PREFIX = "Basic ";
    private static final ApiProblem CHALLENGED = ApiProblem.unauthorized(
            "Peekaboot's dashboard and API need Peekaboot's own credentials, sent with HTTP Basic.");

    private final DashboardCredentials credentials;
    private final CredentialCache cache;

    public DashboardAuthenticationFilter(DashboardCredentials credentials, CredentialCache cache) {
        this.credentials = credentials;
        this.cache = cache;
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

        String header = httpRequest.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !verify(header)) {
            if (header != null) {
                logFailedVerification(httpRequest, header);
            }
            challenge(httpRequest, httpResponse);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean verify(String header) {
        if (cache.isKnownGood(header)) {
            return true;
        }
        Presented presented = parse(header);
        if (presented == null
                || !constantTimeEquals(credentials.username(), presented.username())
                || !credentials.passwordHash().matches(presented.password())) {
            return false;
        }
        cache.remember(header);
        return true;
    }

    private static @Nullable Presented parse(String header) {
        if (!header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(
                    Base64.getDecoder()
                            .decode(header.substring(BASIC_PREFIX.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            return null;
        }
        return new Presented(decoded.substring(0, separator), decoded.substring(separator + 1));
    }

    /** DEBUG: a mistyped password is routine, and an operator chasing a brute-force attempt can turn this logger up. */
    private static void logFailedVerification(HttpServletRequest request, String header) {
        Presented presented = parse(header);
        log.debug(
                "Rejected credentials for {} - presented username was {}",
                request.getRequestURI(),
                presented != null ? presented.username() : "<unparseable>");
    }

    private record Presented(String username, String password) {}

    /**
     * A 401 with a problem body, set rather than sent: {@code sendError} would dispatch to the error
     * page and re-enter the chain. The guard answers before the API's header interceptor runs, so it
     * sets {@link ApiSecurityHeaders} itself. An include gets nothing from it. An include cannot set
     * the status, so the including response keeps its own, and a body would land inside the
     * including page. A committed response gets no body either, since its status is already sent
     * and it would only gain trailing bytes.
     */
    private static void challenge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(CHALLENGE_HEADER, CHALLENGE);
        ApiSecurityHeaders.apply(response);
        if (request.getDispatcherType() == DispatcherType.INCLUDE || response.isCommitted()) {
            response.setContentLength(0);
            return;
        }
        byte[] body = PeekabootJson.MAPPER.writeValueAsBytes(CHALLENGED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * Belt-and-braces over a value that is public anyway - the username is
     * {@code <application name>-admin}, visible by construction. The {@code ||} short-circuit in
     * {@link #verify} is deliberate: skipping the 210,000-iteration derivation on a wrong username
     * means that answer costs microseconds rather than the ~100ms a right one takes, so a wrong
     * username never buys an attacker a free PBKDF2 derivation.
     */
    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
