package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard reads camelCase names, tests some fields with {@code !== null} and parses
 * every instant as an ISO-8601 string. An application is free to configure its own Jackson
 * differently - this context does all three at once - and Peekaboot's API must not follow,
 * because {@code PeekabootJsonMessageConverter} serialises Peekaboot's own types with
 * Peekaboot's own mapper. {@link ProbeController} is the control: the same settings visibly
 * reshape the application's own JSON in the same context.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jackson.property-naming-strategy=SNAKE_CASE",
            "spring.jackson.default-property-inclusion=non_null",
            "spring.jackson.datatype.datetime.write-dates-as-timestamps=true"
        })
@ActiveProfiles("test")
@Import(HostJacksonSettingsIT.ProbeController.class)
class HostJacksonSettingsIT {

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode getJson(String uri) {
        String body = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri(uri)
                .retrieve()
                .body(String.class);
        return jsonMapper.readTree(body);
    }

    @Test
    void theApplicationsOwnJsonFollowsItsJacksonSettings() {
        JsonNode probe = getJson("/host-json-probe");

        assertThat(probe.has("first_name")).isTrue();
        assertThat(probe.has("note")).as("non_null drops the null field").isFalse();
        assertThat(probe.path("created_at").isNumber())
                .as("write-dates-as-timestamps renders the instant as a number")
                .isTrue();
    }

    @Test
    void traceInsightsKeepTheirCamelCaseNames() {
        JsonNode insights = getJson("/peekaboot/api/traces/insights");

        assertThat(insights.has("bucketCounts")).isTrue();
        assertThat(insights.has("bucket_counts")).isFalse();
    }

    @Test
    void actuatorInsightsKeepCamelCaseNamesNullsAndIsoInstants() {
        JsonNode insights = getJson("/peekaboot/api/actuator/all/insights");

        assertThat(insights.has("dataSources")).isTrue();
        assertThat(insights.has("data_sources")).isFalse();

        // loggers.js decides "configured" by configuredLevel !== null - the null has to be there
        JsonNode unconfiguredLogger = null;
        for (JsonNode group : insights.path("loggers").path("packages")) {
            for (JsonNode logger : group.path("loggers")) {
                if (logger.path("configuredLevel").isNull()) {
                    unconfiguredLogger = logger;
                }
            }
        }
        assertThat(unconfiguredLogger)
                .as("a logger with an explicit null configuredLevel")
                .isNotNull();
        assertThat(unconfiguredLogger.has("configuredLevel")).isTrue();

        JsonNode tasks = insights.path("scheduledTasks").path("tasks");
        assertThat(tasks).isNotEmpty();
        JsonNode nextExecution = tasks.get(0).path("nextExecution");
        assertThat(nextExecution.isString()).isTrue();
        assertThatCode(() -> Instant.parse(nextExecution.asString())).doesNotThrowAnyException();
    }

    @RestController
    static class ProbeController {

        record Probe(String firstName, Instant createdAt, String note) {}

        @GetMapping("/host-json-probe")
        Probe probe() {
            return new Probe("Ada", Instant.EPOCH, null);
        }
    }
}
