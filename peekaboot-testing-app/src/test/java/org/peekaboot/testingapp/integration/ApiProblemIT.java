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
                "/peekaboot/api/traces/insights?limit=abc | limit",
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
    void anUnknownTraceIsNotFoundWithAProblem() {
        Answer answer = fetch("/peekaboot/api/traces/00000000000000000000000000000000/insights");

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
        Answer answer = fetch("/peekaboot/api/traces/insights?limit=abc");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.headers().getCacheControl()).contains("no-store");
        assertThat(answer.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** What the dashboard itself sends stays accepted: its own values, any case, the wildcard. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/peekaboot/api/traces/insights?bucket=errors",
                "/peekaboot/api/traces/insights?rootActionType=*",
                "/peekaboot/api/traces/insights?rootActionType=scheduled_job,MAINTENANCE",
                "/peekaboot/api/traces/insights?bucket="
            })
    void theDashboardsOwnValuesAreAccepted(String path) {
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
