package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.peekaboot.backend.testsupport.Requests.get;

import ch.qos.logback.classic.Level;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.Credentials;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.mock.web.MockHttpServletResponse;

class DashboardAuthenticationFilterTest {

    private static final String PASSWORD = "s3cret";

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final FilterChain chain = (request, response) -> chainCalls.incrementAndGet();

    private LogCapture logs;

    // A wrong-credentials test emits the per-attempt DEBUG line; captured here so it leaves no
    // stray line in the test output, and asserted where a test cares.
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

        filter().doFilter(get("/peekaboot/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"Peekaboot\", charset=\"UTF-8\"");
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void challengesARequestWithTheWrongPassword() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/api/insights");
        request.addHeader("Authorization", basic("orders-admin", "wrong"));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalls).hasValue(0);
    }

    /** A brute-force attempt must leave a trace even though {@code CredentialCache} never caches it. */
    @Test
    void logsAFailedVerificationAtDebugNamingThePathAndPresentedUsername() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/api/insights");
        request.addHeader("Authorization", basic("orders-admin", "wrong"));

        filter().doFilter(request, response, chain);

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

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseHeaderIsNotBasic() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", "Bearer abcdef");

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseHeaderIsNotDecodable() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", "Basic not base64!");

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void challengesARequestWhoseDecodedPayloadHasNoColon() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("nocolonhere".getBytes(StandardCharsets.UTF_8)));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void passesARequestWithTheRightCredentials() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));

        filter().doFilter(request, response, chain);

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

        filter(passwordWithColon).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    /** The one branch that returns true without looking at either the username or the password. */
    @Test
    void aSecondRequestWithTheSameHeaderIsServedFromTheCache() throws Exception {
        var filter = filter();
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

    @Test
    void refusesANonHttpRequest() {
        ServletRequest request = mock(ServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);

        assertThatThrownBy(() -> filter().doFilter(request, response, chain)).isInstanceOf(ServletException.class);

        assertThat(chainCalls).hasValue(0);
    }

    /** A challenge is the first step of every browser's Basic handshake, not news. */
    @Test
    void logsNothingWhenItChallengesARequestWithoutCredentials() throws Exception {
        filter().doFilter(get("/peekaboot/"), new MockHttpServletResponse(), chain);

        assertThat(logs.appender().list).isEmpty();
    }

    private static DashboardAuthenticationFilter filter() {
        return filter(PASSWORD);
    }

    private static DashboardAuthenticationFilter filter(String password) {
        return new DashboardAuthenticationFilter(Credentials.loaded(password), new CredentialCache());
    }

    private static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
