package net.osslabz.peekaboot.backend.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterPathMatcherTest {

    @Test
    void shouldSkip_staticPaths_returnsTrue() {
        assertThat(FilterPathMatcher.shouldSkip("/static/")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/static/css/app.css")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/webjars/")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/webjars/bootstrap/5.0.0/css/bootstrap.min.css")).isTrue();
    }

    @Test
    void shouldSkip_actuatorPaths_returnsTrue() {
        assertThat(FilterPathMatcher.shouldSkip("/actuator/")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/actuator/health")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/actuator/info")).isTrue();
    }

    @Test
    void shouldSkip_peekabootPaths_returnsTrue() {
        assertThat(FilterPathMatcher.shouldSkip("/peekaboot/")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/peekaboot/api/v1/traces")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/peekaboot/dashboard")).isTrue();
    }

    @Test
    void shouldSkip_errorPath_returnsTrue() {
        assertThat(FilterPathMatcher.shouldSkip("/error")).isTrue();
        assertThat(FilterPathMatcher.shouldSkip("/error/404")).isTrue();
    }

    @Test
    void shouldSkip_appPaths_returnsFalse() {
        assertThat(FilterPathMatcher.shouldSkip("/api/users")).isFalse();
        assertThat(FilterPathMatcher.shouldSkip("/api/v1/products")).isFalse();
        assertThat(FilterPathMatcher.shouldSkip("/persons")).isFalse();
        assertThat(FilterPathMatcher.shouldSkip("/")).isFalse();
        assertThat(FilterPathMatcher.shouldSkip("/home")).isFalse();
        assertThat(FilterPathMatcher.shouldSkip("/dashboard")).isFalse();
    }

    @Test
    void excludedPrefixes_containsExpectedValues() {
        assertThat(FilterPathMatcher.EXCLUDED_PREFIXES).containsExactlyInAnyOrder(
                "/static/",
                "/webjars/",
                "/actuator/",
                "/peekaboot/",
                "/error"
        );
    }
}
