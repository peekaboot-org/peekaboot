package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/** Every error the API answers is a problem body an agent can read without guessing, over real HTTP. */
class ApiProblemIT extends TestingAppITBase {

    record Answer(int status, MediaType contentType, JsonNode body, HttpHeaders headers) {}

    /** The running application's own mappings, so an endpoint added later is held to the same rule. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "/peekaboot/api/traces?bucket=eror | bucket",
                "/peekaboot/api/traces?rootActionType=HTTP_REQUEST,NOPE | rootActionType",
                "/peekaboot/api/traces?limit=abc | limit",
                "/peekaboot/api/traces?limit=0 | limit",
                "/peekaboot/api/traces?limit=10001 | limit",
                "/peekaboot/api/traces?limit=1&limit=2 | limit",
                "/peekaboot/api/traces?view=sumary | view",
                "/peekaboot/api/traces?cursor=x | cursor",
                "/peekaboot/api/traces?cursor=bC43LjU | cursor",
                "/peekaboot/api/traces?statusCode=abc | statusCode",
                "/peekaboot/api/traces?statusCode=600 | statusCode",
                "/peekaboot/api/traces?outcome=FAILED | outcome",
                "/peekaboot/api/traces?minDurationMs=-1 | minDurationMs",
                "/peekaboot/api/traces?since=yesterday | since",
                "/peekaboot/api/traces?since=2026-11-01T10:00:00Z&until=2026-11-01T09:00:00Z | until",
                "/peekaboot/api/logs?level=loud | level",
                "/peekaboot/api/logs?limit=0 | limit",
                "/peekaboot/api/logs?since=yesterday | since",
                "/peekaboot/api/logs?cursor=dC43LjEuMS4tMQ | cursor",
                "/peekaboot/api/logs?q=a&q=b | q",
                "/peekaboot/api/actuator/all/insights?unmask=maybe | unmask",
                "/peekaboot/api/insights/data?level=abc | level",
                "/peekaboot/api/insights/data | level",
                "/peekaboot/api/insights/data?level=99 | level",
                "/peekaboot/api/insights/data?level=0&level=1 | level",
                "/peekaboot/api/insights/data?level=0&view=all | view",
                "/peekaboot/api/insights/data?level=0&series=no.such.series | series",
                "/peekaboot/api/insights/data?level=0&series=cpu.process,,heap.used | series",
                "/peekaboot/api/insights/data?level=0&since=yesterday | since",
                "/peekaboot/api/errors?since=yesterday | since",
                "/peekaboot/api/errors?route=/a&route=/b | route",
                "/peekaboot/api/traces?buckett=errors | buckett",
                "/peekaboot/api/features?x=1 | x",
                "/peekaboot/api/traces/00000000000000000000000000000000?view=full | view",
                "/peekaboot/api/actuator/all/insights?locale=en&locale=de | locale"
            })
    void aBadParameterIsRefusedWithAProblemNamingIt(String path, String parameter) {
        Answer answer = fetch(path);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(answer.body().path("type").asString("")).isEqualTo("about:blank");
        assertThat(answer.body().path("title").asString("")).isEqualTo("Bad Request");
        assertThat(answer.body().path("status").asInt()).isEqualTo(400);
        assertThat(answer.body().path("parameter").asString("")).isEqualTo(parameter);
        assertThat(answer.body().path("detail").asString("")).isNotBlank();
    }

    @Test
    void anUnknownBucketNamesTheAcceptedValues() {
        assertThat(fetch("/peekaboot/api/traces?bucket=eror")
                        .body()
                        .path("detail")
                        .asString(""))
                .isEqualTo("Unknown bucket 'eror'. Accepted: ALL, ERRORS, SLOW.");
    }

    @Test
    void anUnknownViewNamesTheAcceptedValues() {
        assertThat(fetch("/peekaboot/api/traces?view=sumary")
                        .body()
                        .path("detail")
                        .asString(""))
                .isEqualTo("Unknown view 'sumary'. Accepted: SUMMARY, FULL.");
    }

    @Test
    void anOutOfRangeLimitNamesItsRange() {
        assertThat(fetch("/peekaboot/api/traces?limit=0").body().path("detail").asString(""))
                .isEqualTo("limit must be an integer from 1 to 10000, got '0'.");
    }

    @Test
    void anUnencodedPlusInAnOffsetSaysHowToSendIt() {
        Answer answer = fetch("/peekaboot/api/traces?since=2026-11-01T10:00:00+01:00");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body().path("parameter").asString("")).isEqualTo("since");
        assertThat(answer.body().path("detail").asString("")).endsWith("Send a + in an offset as %2B.");
    }

    @Test
    void theWildcardInAListOfTypesIsRefusedAsAcceptedOnlyOnItsOwn() {
        assertThat(fetch("/peekaboot/api/traces?rootActionType=HTTP_REQUEST,*")
                        .body()
                        .path("detail")
                        .asString(""))
                .startsWith("Unknown rootActionType '*'.")
                .endsWith(", UNKNOWN, * on its own.");
    }

    @Test
    void aRepeatedParameterNamesHowOftenItCame() {
        assertThat(fetch("/peekaboot/api/traces?limit=1&limit=2")
                        .body()
                        .path("detail")
                        .asString(""))
                .isEqualTo("limit was sent 2 times. Send it once.");
    }

    @Test
    void aMisspelledParameterNamesWhatTheEndpointTakes() {
        assertThat(fetch("/peekaboot/api/errors?rout=/a").body().path("detail").asString(""))
                .isEqualTo("Unknown parameter 'rout'. Accepted: since, route.");
    }

    /**
     * Every endpoint under /peekaboot/api refuses a parameter it does not take. The paths come
     * from the application's mappings, so a new endpoint needs no row here. The event stream goes
     * last: an endpoint that ignored the parameter would answer with a stream that never ends.
     */
    @Test
    void everyEndpointRefusesAParameterItDoesNotTake() {
        List<String> paths = ApiMappings.paths(mappings);
        assertThat(paths)
                .as("the API's mappings")
                .contains(
                        "/peekaboot/api/features",
                        "/peekaboot/api/traces/{traceId}",
                        "/peekaboot/api/lifecycle/runs",
                        "/peekaboot/api/insights/stream");

        for (String path : paths) {
            Answer answer = fetch(ApiMappings.withUnknownParameter(path));

            assertThat(answer.status()).as(path).isEqualTo(400);
            assertThat(answer.contentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(answer.body().path("parameter").asString("")).as(path).isEqualTo("peekabootTypo");
            assertThat(answer.body().path("detail").asString(""))
                    .as(path)
                    .startsWith("Unknown parameter 'peekabootTypo'. Accepted: ");
        }
    }

    /** Spring Security's login sends a browser back to an API URL with this marker; refusing it would break the login. */
    @ParameterizedTest
    @ValueSource(strings = {"/peekaboot/api/features?continue", "/peekaboot/api/traces?limit=50&continue"})
    void springSecuritysLoginReturnMarkerIsAccepted(String path) {
        assertThat(fetch(path).status()).isEqualTo(200);
    }

    @Test
    void anUnknownTraceIsNotFoundWithAProblem() {
        Answer answer = fetch("/peekaboot/api/traces/00000000000000000000000000000000");

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(answer.body().path("detail").asString("")).contains("00000000000000000000000000000000");
        assertThat(answer.body().path("parameter").isNull())
                .as("parameter is null, not absent")
                .isTrue();
    }

    /** The test profile configures three insights levels. */
    @Test
    void aMissingLevelNamesTheAcceptedRange() {
        assertThat(fetch("/peekaboot/api/insights/data").body().path("detail").asString(""))
                .isEqualTo("level is required. Accepted: 0 to 2.");
    }

    /** The API's security headers are set before the handler runs, so its refusals carry them too. */
    @ParameterizedTest
    @ValueSource(strings = {"/peekaboot/api/traces?limit=abc", "/peekaboot/api/traces?limitt=1"})
    void aProblemIsNeitherStoredNorSniffed(String path) {
        Answer answer = fetch(path);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.headers().getCacheControl()).contains("no-store");
        assertThat(answer.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** What the dashboard itself sends stays accepted: every parameter it sends, its own values, any case, the wildcard. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/peekaboot/api/traces?bucket=errors",
                "/peekaboot/api/traces?rootActionType=*",
                "/peekaboot/api/traces?rootActionType=scheduled_job,MAINTENANCE",
                "/peekaboot/api/traces?bucket=",
                "/peekaboot/api/traces?view=full",
                "/peekaboot/api/traces?view=Summary",
                "/peekaboot/api/traces?limit=50",
                "/peekaboot/api/traces?limit=50&bucket=errors&rootActionType=SCHEDULED_JOB,MAINTENANCE&rootOperation=fixedRate",
                "/peekaboot/api/actuator/all/insights?locale=en-US&unmask=true",
                "/peekaboot/api/insights/data?level=0&view=full"
            })
    void theDashboardsOwnValuesAreAccepted(String path) {
        assertThat(fetch(path).status()).isEqualTo(200);
    }

    /** The log search runs with no parameter at all, and reads a level in any case. */
    @ParameterizedTest
    @ValueSource(strings = {"/peekaboot/api/logs", "/peekaboot/api/logs?level=Warn"})
    void theLogSearchAcceptsItsOwnValues(String path) {
        assertThat(fetch(path).status()).isEqualTo(200);
    }

    private Answer fetch(String path) {
        return api.restClient()
                .get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Answer(
                        response.getStatusCode().value(),
                        response.getHeaders().getContentType(),
                        PeekabootApi.readJson(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)),
                        HttpHeaders.copyOf(response.getHeaders())));
    }
}
