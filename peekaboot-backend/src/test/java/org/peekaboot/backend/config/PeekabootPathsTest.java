package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PeekabootPathsTest {

    @Test
    void staticPathsAreExcluded() {
        assertThat(PeekabootPaths.isExcluded("/static/")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/static/css/app.css")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/webjars/")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/webjars/bootstrap/5.0.0/css/bootstrap.min.css"))
                .isTrue();
    }

    @Test
    void actuatorPathsAreExcluded() {
        assertThat(PeekabootPaths.isExcluded("/actuator/")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/actuator/health")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/actuator/info")).isTrue();
    }

    @Test
    void peekabootsOwnPathsAreExcluded() {
        assertThat(PeekabootPaths.isExcluded("/peekaboot/")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/peekaboot/api/v1/traces")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/peekaboot/dashboard")).isTrue();
    }

    @Test
    void errorPathsAreExcluded() {
        assertThat(PeekabootPaths.isExcluded("/error")).isTrue();
        assertThat(PeekabootPaths.isExcluded("/error/404")).isTrue();
    }

    @Test
    void applicationPathsAreNotExcluded() {
        assertThat(PeekabootPaths.isExcluded("/api/users")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/api/v1/products")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/persons")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/home")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/dashboard")).isFalse();
    }

    @Test
    void pathsMerelyStartingWithErrorAreNotExcluded() {
        assertThat(PeekabootPaths.isExcluded("/errors")).isFalse();
        assertThat(PeekabootPaths.isExcluded("/error-report")).isFalse();
    }

    @Test
    void excludedPrefixesContainsExpectedValues() {
        assertThat(PeekabootPaths.EXCLUDED_PREFIXES)
                .containsExactlyInAnyOrder("/static/", "/webjars/", "/actuator/", "/peekaboot/", "/error/");
    }

    /** The interceptor's MVC patterns are the same exclusions, so the two can never drift apart. */
    @Test
    void excludePatternsMirrorTheExcludedPrefixes() {
        assertThat(PeekabootPaths.excludePatterns())
                .containsExactlyInAnyOrder(
                        "/static/**", "/webjars/**", "/actuator/**", "/peekaboot/**", "/error/**", "/error");
    }

    /**
     * The request URI carries the context path and whatever the client sent; the servlet path
     * and path info are what the container mapped after decoding and normalising, so a
     * context path or a {@code ..} segment cannot hide Peekaboot's own endpoints.
     */
    @Test
    void pathWithinApplicationIsTheServletPathPlusPathInfo() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/x/../peekaboot/api/traces");
        request.setContextPath("/app");
        request.setServletPath("/peekaboot/api/traces");

        assertThat(PeekabootPaths.pathWithinApplication(request)).isEqualTo("/peekaboot/api/traces");
    }

    @Test
    void pathWithinApplicationAppendsPathInfoWhenTheServletIsMappedToAPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/persons");
        request.setServletPath("/api");
        request.setPathInfo("/persons");

        assertThat(PeekabootPaths.pathWithinApplication(request)).isEqualTo("/api/persons");
    }

    @Test
    void basePathSitsBehindTheRequestsContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/persons");
        request.setContextPath("/app");

        assertThat(PeekabootPaths.basePath(request)).isEqualTo("/app/peekaboot");
    }

    @Test
    void basePathIsThePrefixItselfWithoutAContextPath() {
        assertThat(PeekabootPaths.basePath(new MockHttpServletRequest("GET", "/persons")))
                .isEqualTo("/peekaboot");
    }
}
