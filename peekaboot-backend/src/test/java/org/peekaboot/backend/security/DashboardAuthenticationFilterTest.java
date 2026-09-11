package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DashboardAuthenticationFilterTest {

    private static final String PASSWORD = "s3cret";

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final FilterChain chain = (request, response) -> chainCalls.incrementAndGet();

    private LogCapture logs;

    // Every challenge-path test emits the filter's one-time WARN, and a wrong-credentials one
    // also emits the per-attempt DEBUG line; captured here so neither leaves a stray line in the
    // test output, with both asserted separately where a test cares.
    @BeforeEach
    void captureLogs() {
        logs = LogCapture.attach(DashboardAuthenticationFilter.class, Level.DEBUG);
    }

    @AfterEach
    void closeLogs() {
        logs.close();
    }

    @Test
    void challengesARequestWithNoCredentials() throws Exception {
        var response = new MockHttpServletResponse();

        filter(new NeverAuthenticated()).doFilter(get("/peekaboot/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"Peekaboot\", charset=\"UTF-8\"");
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void challengesARequestWithTheWrongPassword() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/api/insights");
        request.addHeader("Authorization", basic("orders-admin", "wrong"));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalls).hasValue(0);
    }

    /** A brute-force attempt must leave a trace even though {@code CredentialCache} never caches it. */
    @Test
    void logsAFailedVerificationAtDebugNamingThePathAndPresentedUsername() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/api/insights");
        request.addHeader("Authorization", basic("orders-admin", "wrong"));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(logs.appender().list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("/peekaboot/api/insights")
                    .contains("orders-admin")
                    .doesNotContain("wrong")
                    .doesNotContain(PASSWORD);
        });
    }

    @Test
    void challengesARequestWithTheWrongUsername() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("someone-else", PASSWORD));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseHeaderIsNotBasic() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", "Bearer abcdef");

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseHeaderIsNotDecodable() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", "Basic not base64!");

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseDecodedPayloadHasNoColon() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("nocolonhere".getBytes(StandardCharsets.UTF_8)));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void passesARequestWithTheRightCredentials() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    /** Everything after the FIRST colon is the password; a password with its own colons must survive intact. */
    @Test
    void aPasswordContainingAColonSurvivesIntact() throws Exception {
        var response = new MockHttpServletResponse();
        var passwordWithColon = "s3:cr:et";
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", passwordWithColon));

        filter(new NeverAuthenticated(), passwordWithColon).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    /** The one branch that returns true without looking at either the username or the password. */
    @Test
    void aSecondRequestWithTheSameHeaderIsServedFromTheCache() throws Exception {
        var filter = filter(new NeverAuthenticated());
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var secondResponse = new MockHttpServletResponse();
        var secondRequest = get("/peekaboot/");
        secondRequest.addHeader("Authorization", basic("orders-admin", PASSWORD));
        filter.doFilter(secondRequest, secondResponse, chain);

        assertThat(secondResponse.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(2);
    }

    /** The application's own security has already spoken; the guard is not a second opinion. */
    @Test
    void standsDownWhenTheRequestArrivedAuthenticated() throws Exception {
        var response = new MockHttpServletResponse();
        var recorder = new RecordingAuthentication();
        recorder.markAlreadyAuthenticated();

        filter(recorder).doFilter(get("/peekaboot/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
        assertThat(recorder.cleared).isFalse();
    }

    @Test
    void recordsAndThenClearsItsOwnAuthentication() throws Exception {
        var recorder = new RecordingAuthentication();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));

        filter(recorder).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(recorder.recorded).isEqualTo("orders-admin");
        assertThat(recorder.cleared).isTrue();
    }

    /** clear() wipes the whole SecurityContext; firing it on a request this filter never authenticated is a bug. */
    @Test
    void doesNotClearOnTheChallengePath() throws Exception {
        var recorder = new RecordingAuthentication();

        filter(recorder).doFilter(get("/peekaboot/"), new MockHttpServletResponse(), chain);

        assertThat(recorder.cleared).isFalse();
    }

    @Test
    void refusesANonHttpRequest() {
        ServletRequest request = mock(ServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);

        assertThatThrownBy(() -> filter(new NeverAuthenticated()).doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void warnsOnlyOnceAcrossRepeatedChallenges() throws Exception {
        var filter = filter(new NeverAuthenticated());

        filter.doFilter(get("/peekaboot/"), new MockHttpServletResponse(), chain);
        filter.doFilter(get("/peekaboot/api/insights"), new MockHttpServletResponse(), chain);

        assertThat(logs.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("/peekaboot/").doesNotContain("/peekaboot/api/insights");
        });
    }

    private static DashboardAuthenticationFilter filter(RequestAuthentication requestAuthentication) {
        return filter(requestAuthentication, PASSWORD);
    }

    private static DashboardAuthenticationFilter filter(RequestAuthentication requestAuthentication, String password) {
        var credentials = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of(password),
                null,
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.LOADED);
        return new DashboardAuthenticationFilter(credentials, new CredentialCache(), requestAuthentication);
    }

    private static MockHttpServletRequest get(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingAuthentication implements RequestAuthentication {
        private boolean alreadyAuthenticated;
        private String recorded;
        private boolean cleared;

        void markAlreadyAuthenticated() {
            alreadyAuthenticated = true;
        }

        @Override
        public boolean alreadyAuthenticated() {
            return alreadyAuthenticated;
        }

        @Override
        public void record(String username) {
            recorded = username;
        }

        @Override
        public void clear() {
            cleared = true;
        }
    }
}
