package org.peekaboot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.problem.ApiProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admits a request that carries Peekaboot's own credentials over HTTP Basic and challenges every other. */
public final class CredentialsAccess implements DashboardAccess {

    // the guard's logger, so the level an operator set for it still shows the rejected attempts
    private static final Logger log = LoggerFactory.getLogger(DashboardAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CHALLENGE_HEADER = "WWW-Authenticate";
    private static final String CHALLENGE = "Basic realm=\"Peekaboot\", charset=\"UTF-8\"";
    private static final String BASIC_PREFIX = "Basic ";
    private static final ApiProblem CHALLENGED = ApiProblem.unauthorized(
            "Peekaboot's dashboard and API need Peekaboot's own credentials, sent with HTTP Basic.");

    private final DashboardCredentials credentials;
    private final CredentialCache cache;

    public CredentialsAccess(DashboardCredentials credentials, CredentialCache cache) {
        this.credentials = credentials;
        this.cache = cache;
    }

    @Override
    public boolean admit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && verify(header)) {
            return true;
        }
        if (header != null) {
            logFailedVerification(request, header);
        }
        challenge(request, response);
        return false;
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

    /** A 401 that tells the client how to sign in, see {@link Refusals#answer}. */
    private static void challenge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(CHALLENGE_HEADER, CHALLENGE);
        Refusals.answer(request, response, CHALLENGED);
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
