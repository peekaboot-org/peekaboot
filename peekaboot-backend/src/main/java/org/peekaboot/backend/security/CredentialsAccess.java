package org.peekaboot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");

    private final DashboardCredentials credentials;
    private final CredentialCache cache;
    private final DerivationGate gate;
    private final AtomicBoolean shedLogged = new AtomicBoolean();

    public CredentialsAccess(DashboardCredentials credentials, CredentialCache cache) {
        this(credentials, cache, new DerivationGate(DerivationGate.MAX_CONCURRENT));
    }

    CredentialsAccess(DashboardCredentials credentials, CredentialCache cache, DerivationGate gate) {
        this.credentials = credentials;
        this.cache = cache;
        this.gate = gate;
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
        if (presented == null || !constantTimeEquals(credentials.username(), presented.username())) {
            return false;
        }
        return switch (gate.check(header, () -> credentials.passwordHash().matches(presented.password()))) {
            case MATCHED -> {
                cache.remember(header);
                yield true;
            }
            case REJECTED -> false;
            case SHED -> {
                logShedOnce();
                yield false;
            }
        };
    }

    /** Once, because every shed sign-in after the first says the same and an attacker sets the rate. */
    private void logShedOnce() {
        if (shedLogged.compareAndSet(false, true)) {
            log.warn(
                    "Peekaboot refused a sign-in unchecked because {} password checks were already running."
                            + " It logs this once.",
                    gate.maxConcurrent());
        }
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
                presented != null ? withoutControlCharacters(presented.username()) : "<unparseable>");
    }

    /** The client chooses the username, and a line break or an escape sequence in it would forge log lines. */
    private static String withoutControlCharacters(String presentedUsername) {
        return CONTROL_CHARACTERS.matcher(presentedUsername).replaceAll("?");
    }

    private record Presented(String username, String password) {}

    /** A 401 that tells the client how to sign in, see {@link Refusals#answer}. */
    private static void challenge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(CHALLENGE_HEADER, CHALLENGE);
        Refusals.answer(request, response, CHALLENGED);
    }

    /**
     * Belt-and-braces over a value that is public anyway - the username is
     * {@code <application name>-admin}, visible by construction. {@link #verify} answers a wrong
     * username before it asks the {@link DerivationGate}, deliberately: that answer costs
     * microseconds rather than the ~100ms a right one takes, so a wrong username buys an attacker
     * neither a PBKDF2 derivation nor one of the gate's slots.
     */
    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
