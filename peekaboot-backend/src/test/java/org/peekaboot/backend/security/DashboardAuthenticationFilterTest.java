package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DashboardAuthenticationFilterTest {

    private static final String PASSWORD = "s3cret";

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final FilterChain chain = (request, response) -> chainCalls.incrementAndGet();

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
    void passesARequestWithTheRightCredentials() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));

        filter(new NeverAuthenticated()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    /** The application's own security has already spoken; the guard is not a second opinion. */
    @Test
    void standsDownWhenTheRequestArrivedAuthenticated() throws Exception {
        var response = new MockHttpServletResponse();

        filter(new AlwaysAuthenticated()).doFilter(get("/peekaboot/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
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

    private static DashboardAuthenticationFilter filter(RequestAuthentication requestAuthentication) {
        var credentials = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of(PASSWORD),
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

    private static final class AlwaysAuthenticated implements RequestAuthentication {
        @Override
        public boolean alreadyAuthenticated() {
            return true;
        }

        @Override
        public void record(String username) {}

        @Override
        public void clear() {}
    }

    private static final class RecordingAuthentication implements RequestAuthentication {
        private String recorded;
        private boolean cleared;

        @Override
        public boolean alreadyAuthenticated() {
            return false;
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
