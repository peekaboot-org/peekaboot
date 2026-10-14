package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Swagger UI, curl and fetch() send {@code Accept: *}{@code /*}; they must get the JSON error
 * body, never Peekaboot's page. Only a client naming text/html gets the page, on both the
 * default path through {@code BasicErrorController} and the override path that outranks it.
 */
class ErrorPageContentNegotiationIT {

    private record ErrorResponse(HttpStatusCode status, MediaType contentType, String body) {}

    private static ErrorResponse fetch(int port, String path, String accept) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Accept", accept)
                .exchange((request, response) -> new ErrorResponse(
                        response.getStatusCode(),
                        response.getHeaders().getContentType(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
    }

    private static ErrorResponse fetchBoom(int port, String accept) {
        return fetch(port, "/boom", accept);
    }

    @Nested
    @SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    class DefaultPath {

        @LocalServerPort
        private int port;

        @Test
        void aWildcardClientGetsJson() {
            ErrorResponse response = fetchBoom(port, "*/*");

            assertThat(response.contentType()).isNotNull();
            assertThat(response.contentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                    .isTrue();
            assertThat(response.body()).doesNotContain("pk-error");
        }

        @Test
        void anHtmlClientGetsThePage() {
            assertThat(fetchBoom(port, "text/html").body()).contains("pk-error");
        }
    }

    @Nested
    @SpringBootTest(
            classes = TestingApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "peekaboot.error-page.override=true")
    @ActiveProfiles("test")
    class OverridePath {

        @LocalServerPort
        private int port;

        @Test
        void aWildcardClientGetsJson() {
            ErrorResponse response = fetchBoom(port, "*/*");

            assertThat(response.contentType()).isNotNull();
            assertThat(response.contentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                    .isTrue();
            assertThat(response.body()).doesNotContain("pk-error");
        }

        @Test
        void anHtmlClientGetsThePage() {
            assertThat(fetchBoom(port, "text/html").body()).contains("pk-error");
        }

        /** PersonController's id is a long; Spring answers "abc" with 400, and so must the page that outranks it. */
        @Test
        void anHtmlClientKeepsSpringsBadRequestForAnUnconvertiblePathVariable() {
            ErrorResponse response = fetch(port, "/persons/abc", "text/html");

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).contains("pk-error");
        }
    }
}
