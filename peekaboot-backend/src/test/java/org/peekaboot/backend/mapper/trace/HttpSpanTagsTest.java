package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpSpanTagsTest {

    /** What Spring Boot's DefaultServerRequestObservationConvention puts on the server span. */
    private static final Map<String, String> SPRING_DEFAULT = Map.of(
            "method", "GET",
            "uri", "/api/users/{id}",
            "status", "200",
            "outcome", "SUCCESS",
            "exception", "none",
            "http.url", "/api/users/42");

    @Test
    void readsSpringsDefaultNamesPreferringTheRequestUriOverTheRoutePattern() {
        assertThat(HttpSpanTags.method(SPRING_DEFAULT)).isEqualTo("GET");
        assertThat(HttpSpanTags.path(SPRING_DEFAULT)).isEqualTo("/api/users/42");
        assertThat(HttpSpanTags.statusCode(SPRING_DEFAULT)).isEqualTo(200);
    }

    @Test
    void fallsBackToTheRoutePatternWhenSpringHasNoRequestUri() {
        assertThat(HttpSpanTags.path(Map.of("method", "GET", "uri", "/api/users/{id}", "status", "200")))
                .isEqualTo("/api/users/{id}");
    }

    @Test
    void readsTheCurrentOpenTelemetryNames() {
        Map<String, String> tags =
                Map.of("http.request.method", "POST", "url.path", "/api/orders", "http.response.status_code", "201");

        assertThat(HttpSpanTags.method(tags)).isEqualTo("POST");
        assertThat(HttpSpanTags.path(tags)).isEqualTo("/api/orders");
        assertThat(HttpSpanTags.statusCode(tags)).isEqualTo(201);
    }

    @Test
    void readsTheSupersededOpenTelemetryNames() {
        Map<String, String> tags = Map.of("http.method", "GET", "http.target", "/api/users", "http.status_code", "200");

        assertThat(HttpSpanTags.method(tags)).isEqualTo("GET");
        assertThat(HttpSpanTags.path(tags)).isEqualTo("/api/users");
        assertThat(HttpSpanTags.statusCode(tags)).isEqualTo(200);
    }

    @Test
    void currentOpenTelemetryNamesWinOverSupersededAndSpringOnes() {
        Map<String, String> tags = Map.of(
                "url.path", "/current",
                "http.target", "/superseded",
                "http.url", "/spring",
                "uri", "/pattern");

        assertThat(HttpSpanTags.path(tags)).isEqualTo("/current");
    }

    /** A client span's http.url is a whole URL; only its path is a path. */
    @Test
    void takesThePathOutOfAFullHttpUrlAndDropsTheQueryString() {
        assertThat(HttpSpanTags.path(Map.of("http.url", "https://api.example.com:8443/orders/7?expand=lines")))
                .isEqualTo("/orders/7");
        assertThat(HttpSpanTags.path(Map.of("http.url", "/orders/7?expand=lines")))
                .isEqualTo("/orders/7");
        assertThat(HttpSpanTags.path(Map.of("http.target", "/orders?page=2"))).isEqualTo("/orders");
    }

    @Test
    void aFullUrlWithoutAPathIsTheRoot() {
        assertThat(HttpSpanTags.path(Map.of("http.url", "https://api.example.com")))
                .isEqualTo("/");
    }

    @Test
    void statusIsNullWhenAbsentOrNotANumber() {
        assertThat(HttpSpanTags.statusCode(Map.of())).isNull();
        assertThat(HttpSpanTags.statusCode(Map.of("status", "not-a-number"))).isNull();
    }

    @Test
    void absentTagsReadAsNull() {
        assertThat(HttpSpanTags.method(Map.of("db.system", "postgresql"))).isNull();
        assertThat(HttpSpanTags.path(Map.of("db.system", "postgresql"))).isNull();
    }

    @Test
    void describesAnHttpRequestUnderEitherNamingScheme() {
        assertThat(HttpSpanTags.describeHttpRequest(SPRING_DEFAULT)).isTrue();
        assertThat(HttpSpanTags.describeHttpRequest(Map.of("method", "GET", "uri", "/x", "status", "200")))
                .isTrue();
        assertThat(HttpSpanTags.describeHttpRequest(Map.of("http.request.method", "GET")))
                .isTrue();
        assertThat(HttpSpanTags.describeHttpRequest(Map.of("rpc.system", "grpc", "method", "Greet")))
                .isFalse();
        assertThat(HttpSpanTags.describeHttpRequest(Map.of())).isFalse();
    }
}
