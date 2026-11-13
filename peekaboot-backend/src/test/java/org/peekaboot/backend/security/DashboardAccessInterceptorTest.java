package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.peekaboot.backend.testsupport.Requests.get;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.controller.PeekabootApiController;
import org.peekaboot.backend.domain.api.ApiDocument;
import org.peekaboot.backend.testsupport.Credentials;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class DashboardAccessInterceptorTest {

    private static final String PASSWORD = "s3cret";

    private final DashboardAccessInterceptor interceptor =
            new DashboardAccessInterceptor(new CredentialsAccess(Credentials.loaded(PASSWORD), new CredentialCache()));

    /** A host's path prefix moved the handler here, where the guard's filter is not mapped. */
    @Test
    void challengesAPeekabootHandlerReachedWithoutCredentials() throws Exception {
        var response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(get("/api/peekaboot/api"), response, peekabootsHandler());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic realm=\"Peekaboot\"");
    }

    @Test
    void admitsAPeekabootHandlerWithTheRightCredentials() throws Exception {
        var response = new MockHttpServletResponse();
        MockHttpServletRequest request = get("/api/peekaboot/api");
        request.addHeader("Authorization", basic("orders-admin", PASSWORD));

        boolean proceed = interceptor.preHandle(request, response, peekabootsHandler());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void neverAsksForAHostsHandler() throws Exception {
        var unasked = new DashboardAccessInterceptor(
                (request, response) -> fail("asked whether a request to the host's own handler may pass"));

        boolean proceed = unasked.preHandle(
                get("/orders"), new MockHttpServletResponse(), new HandlerMethod(new Object(), "toString"));

        assertThat(proceed).isTrue();
    }

    private static HandlerMethod peekabootsHandler() throws Exception {
        return new HandlerMethod(new PeekabootApiController(ApiDocument.bundled(), PeekabootPaths.defaults()), "index");
    }

    private static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
