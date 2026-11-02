package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Every error the API answers is a problem body an agent can read without guessing, over real HTTP. */
class ApiProblemIT extends TestingAppITBase {

    record Answer(int status, MediaType contentType, JsonNode body, HttpHeaders headers) {}

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
                "/peekaboot/api/insights/data?level=99 | level"
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
    @Test
    void aProblemIsNeitherStoredNorSniffed() {
        Answer answer = fetch("/peekaboot/api/traces?limit=abc");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.headers().getCacheControl()).contains("no-store");
        assertThat(answer.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** What the dashboard itself sends stays accepted: its own values, any case, the wildcard. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/peekaboot/api/traces?bucket=errors",
                "/peekaboot/api/traces?rootActionType=*",
                "/peekaboot/api/traces?rootActionType=scheduled_job,MAINTENANCE",
                "/peekaboot/api/traces?bucket=",
                "/peekaboot/api/traces?view=full",
                "/peekaboot/api/traces?view=Summary",
                "/peekaboot/api/traces?limit=50"
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
