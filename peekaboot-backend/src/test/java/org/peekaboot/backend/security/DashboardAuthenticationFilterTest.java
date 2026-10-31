package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.peekaboot.backend.testsupport.Requests.get;

import ch.qos.logback.classic.Level;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.config.PeekabootWebConfig;
import org.peekaboot.backend.problem.ApiProblem;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.problem.ApiProblemHandler;
import org.peekaboot.backend.testsupport.Credentials;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

class DashboardAuthenticationFilterTest {

    private static final String PASSWORD = "s3cret";
    private static final String CHALLENGE_DETAIL =
            "Peekaboot's dashboard and API need Peekaboot's own credentials, sent with HTTP Basic.";

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
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode problem = PeekabootJson.MAPPER.readTree(response.getContentAsByteArray());
        assertThat(problem.path("type").asString("")).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("title").asString("")).isEqualTo("Unauthorized");
        assertThat(problem.path("parameter").isNull()).isTrue();
    }

    /** One problem shape for an agent, whichever of the two answered it. */
    @Test
    void challengesWithTheBytesTheApisOwnProblemHandlerWrites() throws Exception {
        var response = new MockHttpServletResponse();

        filter().doFilter(get("/peekaboot/"), response, chain);

        byte[] handlerBytes = writtenByTheProblemHandler(ApiProblem.unauthorized(CHALLENGE_DETAIL));
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .isEqualTo(new String(handlerBytes, StandardCharsets.UTF_8));
        assertThat(response.getContentLength()).isEqualTo(handlerBytes.length);
    }

    /** The guard answers before the API's own header interceptor runs, so it marks its refusal itself. */
    @Test
    void theChallengeIsNeitherStoredNorSniffed() throws Exception {
        var response = new MockHttpServletResponse();

        filter().doFilter(get("/peekaboot/api/insights"), response, chain);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** An included dashboard's body would land inside the including page; the challenge there stays bare. */
    @Test
    void challengesAnIncludeWithoutABody() throws Exception {
        var response = new MockHttpServletResponse();
        var request = get("/peekaboot/api/insights");
        request.setDispatcherType(DispatcherType.INCLUDE);

        filter().doFilter(request, response, chain);

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(chainCalls).hasValue(0);
    }

    /** A committed response has already sent its status; a problem written now would only trail what the application sent. */
    @Test
    void leavesACommittedResponseWithoutABody() throws Exception {
        var response = new MockHttpServletResponse();
        response.setCommitted(true);

        filter().doFilter(get("/peekaboot/api/insights"), response, chain);

        assertThat(response.getContentAsByteArray()).isEmpty();
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

    /** What the API's own error path writes for {@code problem}: its advice and Peekaboot's converters over real MVC dispatch. */
    private static byte[] writtenByTheProblemHandler(ApiProblem problem) throws Exception {
        HttpMessageConverters.ServerBuilder converters =
                HttpMessageConverters.forServer().registerDefaults();
        new PeekabootWebConfig().configureMessageConverters(converters);
        List<HttpMessageConverter<?>> registered = new ArrayList<>();
        converters.build().forEach(registered::add);
        return MockMvcBuilders.standaloneSetup(new RefusingEndpoint(problem))
                .setControllerAdvice(new ApiProblemHandler())
                .setMessageConverters(registered.toArray(HttpMessageConverter<?>[]::new))
                .build()
                .perform(MockMvcRequestBuilders.get(RefusingEndpoint.PATH))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    @RestController
    static class RefusingEndpoint {
        static final String PATH = "/peekaboot/api/refused";

        private final ApiProblem problem;

        RefusingEndpoint(ApiProblem problem) {
            this.problem = problem;
        }

        @GetMapping(PATH)
        String refuse() {
            throw new ApiProblemException(problem);
        }
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
