package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.errorpage.PeekabootErrorExceptionResolver;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.JsonNode;

/**
 * Swagger UI, curl and fetch() send {@code Accept: *}{@code /*}; they must get the JSON error
 * body, never Peekaboot's page. Only a client naming text/html gets the page, on both the
 * default path through {@code BasicErrorController} and the override path that outranks it.
 */
class ErrorPageContentNegotiationIT {

    /** What Chrome sends for a page typed into its address bar. */
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
            + "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";

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

        /** Peekaboot's API answers a request it refuses with its problem for any Accept that admits JSON, a browser's included. */
        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "/peekaboot/api/traces?peekabootPageTypo=1 | peekabootPageTypo",
                    "/peekaboot/api/openapi.json?peekabootPageTypo=1 | peekabootPageTypo",
                    "/peekaboot/api/actuator/all/insights?unmask=maybe | unmask"
                })
        void aBrowserGetsTheApisProblemForARefusedRequest(String path, String parameter) {
            try (LogCapture logs = LogCapture.attach(PeekabootErrorExceptionResolver.class)) {
                ErrorResponse response = fetch(path, BROWSER_ACCEPT);

                assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
                assertThat(PeekabootApi.readJson(response.body())
                                .path("parameter")
                                .asString(""))
                        .isEqualTo(parameter);
                assertThat(logs.appender().list).as("nothing failed").isEmpty();
            }
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

        /**
         * The page answers Spring's 400 for an unconvertible id. A 400 is the caller's mistake, a
         * CLIENT_ERROR issue on an OK trace, outside Errors, the outcome filter and the digest.
         */
        @Test
        void aBadRequestThePageAnswersIsAClientErrorNotAFailure() {
            String traceId =
                    TraceApiClient.traceIdOf(fetch("/persons/abc", "text/html").headers());

            JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.COMPLETE);

            JsonNode summary = trace.path("summary");
            assertThat(summary.path("request").path("statusCode").asInt()).isEqualTo(400);
            assertThat(summary.path("outcome").asString()).isEqualTo("OK");
            assertThat(summary.path("buckets").valueStream().map(JsonNode::asString))
                    .doesNotContain("ERRORS");
            assertThat(summary.path("cause").isNull()).isTrue();
            assertThat(trace.path("data").path("rootSpan").path("status").asString())
                    .isEqualTo("OK");
            assertThat(SpanTree.issuesOfType(trace, "CLIENT_ERROR"))
                    .singleElement()
                    .satisfies(issue -> assertThat(issue.path("value").asInt()).isEqualTo(400));
            assertThat(traces.listedTraceIds("outcome=error")).doesNotContain(traceId);
            assertThat(api.getJson("/peekaboot/api/errors")
                            .path("errors")
                            .valueStream()
                            .map(entry -> entry.path("lastTraceId").asString()))
                    .as("the digest records only failed traces")
                    .doesNotContain(traceId);
        }

        /** A failure the page answers with 500 still fails the trace and names the handler's exception, unlike the 400 above. */
        @Test
        void aFailureThePageAnswersStillFailsTheTrace() {
            String traceId = TraceApiClient.traceIdOf(fetchBoom("text/html").headers());

            JsonNode summary =
                    traces.awaitTrace(traceId, TraceApiClient.COMPLETE).path("summary");

            assertThat(summary.path("outcome").asString()).isEqualTo("ERROR");
            assertThat(summary.path("buckets").valueStream().map(JsonNode::asString))
                    .contains("ERRORS");
            assertThat(summary.path("cause").path("type").asString()).isEqualTo("java.lang.IllegalStateException");
            assertThat(summary.path("cause").path("message").asString())
                    .isEqualTo("order reconciliation gateway is unreachable");
        }
    }
}
