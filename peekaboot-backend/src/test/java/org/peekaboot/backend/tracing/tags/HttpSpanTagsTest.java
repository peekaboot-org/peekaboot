package org.peekaboot.backend.tracing.tags;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /** A query right after the authority leaves no path segment to take; a bare query leaves none at all. */
    @Test
    void aUrlWithAQueryButNoPathReadsAsTheRootAndABareQueryAsEmpty() {
        assertThat(HttpSpanTags.path(Map.of("http.url", "https://h?x=1"))).isEqualTo("/");
        assertThat(HttpSpanTags.path(Map.of("http.target", "?x=1"))).isEmpty();
    }

    /** An instrumentation that stringifies the code its own way yields no status rather than a wrong one. */
    @Test
    void statusIsNullWhenAbsentOrNotAPlainInteger() {
        assertThat(HttpSpanTags.statusCode(Map.of())).isNull();
        assertThat(HttpSpanTags.statusCode(Map.of("status", "not-a-number"))).isNull();
        assertThat(HttpSpanTags.statusCode(Map.of("status", "200.0"))).isNull();
        assertThat(HttpSpanTags.statusCode(Map.of("status", " 200"))).isNull();
    }

    @Test
    void absentTagsReadAsNull() {
        assertThat(HttpSpanTags.method(Map.of("db.system", "postgresql"))).isNull();
        assertThat(HttpSpanTags.path(Map.of("db.system", "postgresql"))).isNull();
        assertThat(HttpSpanTags.route(Map.of("db.system", "postgresql"))).isNull();
    }

    /** Spring's convention writes a placeholder word where no pattern matched, and a placeholder names no route. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "http.route | /persons/{id} | /persons/{id}",
                "uri        | /orders/{id}  | /orders/{id}",
                "uri        | UNKNOWN       | ",
                "uri        | NOT_FOUND     | ",
                "uri        | REDIRECTION   | ",
                "uri        | root          | "
            })
    void routeIsTheMatchedPattern(String key, String value, String route) {
        assertThat(HttpSpanTags.route(Map.of(key, value))).isEqualTo(route);
    }

    @Test
    void describesAnHttpRequestUnderEitherNamingScheme() {
        assertThat(HttpSpanTags.describesHttpRequest(SPRING_DEFAULT)).isTrue();
        assertThat(HttpSpanTags.describesHttpRequest(Map.of("method", "GET", "uri", "/x", "status", "200")))
                .isTrue();
        assertThat(HttpSpanTags.describesHttpRequest(Map.of("http.request.method", "GET")))
                .isTrue();
        assertThat(HttpSpanTags.describesHttpRequest(Map.of("rpc.system", "grpc", "method", "Greet")))
                .isFalse();
        assertThat(HttpSpanTags.describesHttpRequest(Map.of())).isFalse();
    }

    /** Only a server span's own answer counts; a client span's 5xx is a downstream failure the application may have handled. */
    @ParameterizedTest
    @CsvSource({
        "SERVER, http.response.status_code, 500, true",
        "SERVER, status, 503, true",
        "SERVER, http.status_code, 599, true",
        "SERVER, http.status_code, 600, false",
        "SERVER, http.response.status_code, 499, false",
        "SERVER, status, CLIENT_ERROR, false",
        "CLIENT, http.response.status_code, 500, false"
    })
    void isServerErrorForAServerSpansFiveHundredsOnly(Span.Kind kind, String key, String value, boolean expected) {
        assertThat(HttpSpanTags.isServerError(kind, Map.of(key, value))).isEqualTo(expected);
    }

    /** Only a server span's own answer counts, the same rule as for a 5xx. An empty expectation means no status. */
    @ParameterizedTest
    @CsvSource({
        "SERVER, http.response.status_code, 400, 400",
        "SERVER, status, 404, 404",
        "SERVER, http.status_code, 499, 499",
        "SERVER, http.response.status_code, 399, ",
        "SERVER, http.response.status_code, 500, ",
        "SERVER, status, CLIENT_ERROR, ",
        "CLIENT, http.response.status_code, 404, "
    })
    void clientErrorStatusOfAServerSpansFourHundredsOnly(Span.Kind kind, String key, String value, Integer expected) {
        assertThat(HttpSpanTags.clientErrorStatus(kind, Map.of(key, value)))
                .isEqualTo(expected == null ? OptionalInt.empty() : OptionalInt.of(expected));
    }

    @Test
    void aSpanWithoutAKindIsNoServerError() {
        assertThat(HttpSpanTags.isServerError(null, Map.of("http.response.status_code", "500")))
                .isFalse();
    }

    @Test
    void aSpanWithoutAKindOrTagsIsNoClientError() {
        assertThat(HttpSpanTags.clientErrorStatus(null, Map.of("http.response.status_code", "404")))
                .isEmpty();
        assertThat(HttpSpanTags.clientErrorStatus(Span.Kind.SERVER, null)).isEmpty();
    }
}
