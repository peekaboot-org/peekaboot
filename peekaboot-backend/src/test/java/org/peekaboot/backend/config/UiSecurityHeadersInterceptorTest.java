package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UiSecurityHeadersInterceptorTest {

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String X_FRAME_OPTIONS = "X-Frame-Options";
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final UiSecurityHeadersInterceptor interceptor = new UiSecurityHeadersInterceptor();

    @Test
    void setsThePolicyTheFrameBanAndNosniff() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getHeaders(CONTENT_SECURITY_POLICY))
                .containsExactly("default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none';"
                        + " form-action 'self'");
        assertThat(response.getHeaders(X_FRAME_OPTIONS)).containsExactly("DENY");
        assertThat(response.getHeaders(X_CONTENT_TYPE_OPTIONS)).containsExactly("nosniff");
    }

    @Test
    void leavesAPolicyTheHostAlreadySetAlone() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(CONTENT_SECURITY_POLICY, "default-src 'self' https://cdn.example");
        response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN");

        interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(response.getHeaders(CONTENT_SECURITY_POLICY))
                .containsExactly("default-src 'self' https://cdn.example");
        assertThat(response.getHeaders(X_FRAME_OPTIONS)).containsExactly("SAMEORIGIN");
        assertThat(response.getHeaders(X_CONTENT_TYPE_OPTIONS))
                .as("what the host left unset is still added")
                .containsExactly("nosniff");
    }
}
