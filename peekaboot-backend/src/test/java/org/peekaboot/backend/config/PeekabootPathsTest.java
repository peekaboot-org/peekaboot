package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PeekabootPathsTest {

    private final PeekabootPaths paths = PeekabootPaths.defaults();

    @Test
    void staticPathsAreExcluded() {
        assertThat(paths.isExcluded("/static/")).isTrue();
        assertThat(paths.isExcluded("/static/css/app.css")).isTrue();
        assertThat(paths.isExcluded("/webjars/")).isTrue();
        assertThat(paths.isExcluded("/webjars/bootstrap/5.0.0/css/bootstrap.min.css"))
                .isTrue();
    }

    @Test
    void actuatorPathsAreExcluded() {
        assertThat(paths.isExcluded("/actuator/")).isTrue();
        assertThat(paths.isExcluded("/actuator/health")).isTrue();
        assertThat(paths.isExcluded("/actuator/info")).isTrue();
    }

    @Test
    void peekabootsOwnPathsAreExcluded() {
        assertThat(paths.isExcluded("/peekaboot/")).isTrue();
        assertThat(paths.isExcluded("/peekaboot/api/v1/traces")).isTrue();
        assertThat(paths.isExcluded("/peekaboot/dashboard")).isTrue();
    }

    @Test
    void errorPathsAreExcluded() {
        assertThat(paths.isExcluded("/error")).isTrue();
        assertThat(paths.isExcluded("/error/404")).isTrue();
    }

    @Test
    void applicationPathsAreNotExcluded() {
        assertThat(paths.isExcluded("/api/users")).isFalse();
        assertThat(paths.isExcluded("/api/v1/products")).isFalse();
        assertThat(paths.isExcluded("/persons")).isFalse();
        assertThat(paths.isExcluded("/")).isFalse();
        assertThat(paths.isExcluded("/home")).isFalse();
        assertThat(paths.isExcluded("/dashboard")).isFalse();
    }

    @Test
    void pathsMerelyStartingWithErrorAreNotExcluded() {
        assertThat(paths.isExcluded("/errors")).isFalse();
        assertThat(paths.isExcluded("/error-report")).isFalse();
    }

    @Test
    void excludedPrefixesContainsExpectedValues() {
        assertThat(paths.excludedPrefixes())
                .containsExactlyInAnyOrder("/static/", "/webjars/", "/actuator/", "/peekaboot/", "/error/");
    }

    /** The interceptor's MVC patterns are the same exclusions, so the two can never drift apart. */
    @Test
    void excludePatternsMirrorTheExcludedPrefixes() {
        assertThat(paths.excludePatterns())
                .containsExactlyInAnyOrder(
                        "/static/**", "/webjars/**", "/actuator/**", "/peekaboot/**", "/error/**", "/error");
    }

    /** The actuator exclusion follows {@code management.endpoints.web.base-path}, not a fixed prefix. */
    @Test
    void aCustomManagementBasePathReplacesTheActuatorExclusion() {
        PeekabootPaths custom = new PeekabootPaths("/manage", "");

        assertThat(custom.isExcluded("/manage/health")).isTrue();
        assertThat(custom.isExcluded("/actuator/health")).isFalse();
        assertThat(custom.excludePatterns()).contains("/manage/**").doesNotContain("/actuator/**");
    }

    @Test
    void theManagementBasePathIsNormalisedToAPrefix() {
        PeekabootPaths custom = new PeekabootPaths("manage/", "");

        assertThat(custom.isExcluded("/manage/health")).isTrue();
        assertThat(custom.isExcluded("/managed")).isFalse();
    }

    /** Management endpoints at the application root have no prefix of their own; nothing extra is excluded - never everything. */
    @Test
    void aRootManagementBasePathExcludesNothingExtra() {
        PeekabootPaths custom = new PeekabootPaths("/", "");

        assertThat(custom.isExcluded("/health")).isFalse();
        assertThat(custom.isExcluded("/peekaboot/api/v1/traces")).isTrue();
        assertThat(custom.excludedPrefixes())
                .containsExactlyInAnyOrder("/static/", "/webjars/", "/peekaboot/", "/error/");
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

    /** An HTTP span's path tag carries the context path; the exclusions must hold behind it too. */
    @Test
    void requestPathsBehindAContextPathAreExcludedTheSameWay() {
        PeekabootPaths behindContext = new PeekabootPaths("/actuator", "/app");

        assertThat(behindContext.isExcludedRequestPath("/app/actuator/health")).isTrue();
        assertThat(behindContext.isExcludedRequestPath("/app/peekaboot/api/v1/traces"))
                .isTrue();
        assertThat(behindContext.isExcludedRequestPath("/app/api/users")).isFalse();
    }

    @Test
    void aPathMerelyStartingWithTheContextPathTextIsNotStripped() {
        PeekabootPaths behindContext = new PeekabootPaths("/actuator", "/app");

        assertThat(behindContext.isExcludedRequestPath("/application/actuator/health"))
                .isFalse();
        assertThat(behindContext.isExcludedRequestPath("/application/peekaboot/api/v1/traces"))
                .isFalse();
    }

    @Test
    void requestPathsMatchDirectlyAtTheRootContextPath() {
        assertThat(paths.isExcludedRequestPath("/actuator/health")).isTrue();
        assertThat(paths.isExcludedRequestPath("/api/users")).isFalse();
    }

    /** Tolerates the trailing-slash and bare-root spellings the context-path property can carry. */
    @Test
    void theContextPathIsNormalisedToItsPrefixForm() {
        assertThat(new PeekabootPaths("/actuator", "/app/").isExcludedRequestPath("/app/actuator/health"))
                .isTrue();
        assertThat(new PeekabootPaths("/actuator", "/").isExcludedRequestPath("/actuator/health"))
                .isTrue();
    }
}
