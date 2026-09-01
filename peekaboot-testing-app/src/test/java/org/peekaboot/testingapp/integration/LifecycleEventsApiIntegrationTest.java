package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the lifecycle history is actually reachable through the real, auto-configured
 * app. The app runs with storage disabled (the default for this module), so this
 * exercises the in-memory path: the current run's own start, recorded asynchronously
 * off {@code ApplicationReadyEvent} (see LifecycleEventRecorder.onReady), is served
 * without any storage configuration at all.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LifecycleEventsApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BuildProperties buildProperties;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void theRunningApplicationServesItsOwnStartWithEveryBuildFieldItKnows() {
        // The start is recorded through recordWhenLoaded, which hands off to a virtual
        // thread and returns before the event necessarily lands in the log, so poll
        // instead of asserting on the first response.
        JsonNode events = await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> getJson("/peekaboot/api/lifecycle/events").get("events"), node -> !node.isEmpty());

        assertThat(events).hasSize(1);

        JsonNode event = events.get(0);
        assertThat(event.get("type").asText()).isEqualTo("start");
        assertThat(event.get("epochMs").asLong()).isPositive();
        assertThat(event.get("version").asText()).isEqualTo(buildProperties.getVersion());
        assertThat(event.get("branch").asText()).isNotEmpty();
        assertThat(event.get("commitId").asText()).isNotEmpty();
        assertThat(event.get("shortCommitId").asText()).isNotEmpty();
        assertThat(event.get("buildTimeEpochMs").asLong()).isPositive();
        assertThat(event.get("uncleanPrevious").asBoolean()).isFalse();
    }

    private JsonNode getJson(String path) {
        String json = restClient
                .get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(json);
    }
}
