package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;

/**
 * Swagger UI, curl and fetch() send {@code Accept: *}{@code /*}; they must get the JSON error
 * body, never Peekaboot's page. Only a client naming text/html gets the page, on both the
 * default path through {@code BasicErrorController} and the override path that outranks it.
 */
class ErrorPageContentNegotiationIT {

    private static final String OVERRIDE_PAGE_FAILURE =
            "Request processing failed: java.lang.IllegalStateException: order reconciliation gateway is unreachable";

    record ErrorResponse(HttpStatusCode status, HttpHeaders headers, String body) {

        MediaType contentType() {
            return headers.getContentType();
        }
    }

    /** What both paths answer alike; JUnit runs these once per subclass, on that subclass's context. */
    abstract static class EitherPath extends TestingAppITBase {

        @Test
        void aWildcardClientGetsJson() {
            ErrorResponse response = fetchBoom("*/*");

            assertThat(response.contentType()).isNotNull();
            assertThat(response.contentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                    .isTrue();
            assertThat(response.body()).doesNotContain("pk-error");
        }

        @Test
        void anHtmlClientGetsThePage() {
            assertThat(fetchBoom("text/html").body()).contains("pk-error");
        }

        ErrorResponse fetch(String path, String accept) {
            return api.restClient()
                    .get()
                    .uri(path)
                    .header("Accept", accept)
                    .exchange((request, response) -> new ErrorResponse(
                            response.getStatusCode(),
                            HttpHeaders.copyOf(response.getHeaders()),
                            StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
        }

        ErrorResponse fetchBoom(String accept) {
            return fetch("/boom", accept);
        }
    }

    @Nested
    class DefaultPath extends EitherPath {}

    @Nested
    @SpringBootTest(
            classes = TestingApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "peekaboot.error-page.override=true")
    class OverridePath extends EitherPath {

        /** PersonController's id is a long; Spring answers "abc" with 400, and so must the page that outranks it. */
        @Test
        void anHtmlClientKeepsSpringsBadRequestForAnUnconvertiblePathVariable() {
            ErrorResponse response = fetch("/persons/abc", "text/html");

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).contains("pk-error");
        }

        /** The page stands where the container would have logged the failure, so the line lands on the request's trace. */
        @Test
        void logsTheExceptionAgainstTheRequestsTrace() {
            HttpHeaders headers = fetchBoom("text/html").headers();

            traces.awaitTrace(TraceApiClient.traceIdOf(headers), TraceApiClient.errorLogged(OVERRIDE_PAGE_FAILURE));
        }
    }
}
