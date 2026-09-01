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
 * Proves the run history is actually reachable through the real, auto-configured app. The
 * app runs with storage disabled (the default for this module), so this exercises the
 * in-memory path: the current run's own start, recorded asynchronously off {@code
 * ApplicationReadyEvent} (see LifecycleEventRecorder.onReady), is served without any
 * storage configuration at all.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LifecycleRunsApiIntegrationTest {

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
    void theRunningApplicationServesItsOwnRunAsTheOldestUnfinishedOne() {
        // The start is recorded through recordWhenLoaded, which hands off to a virtual
        // thread and returns before the event necessarily lands in the log, so poll
        // instead of asserting on the first response.
        JsonNode runs = await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> getJson("/peekaboot/api/lifecycle/runs").get("runs"), node -> !node.isEmpty());

        assertThat(runs).hasSize(1);

        JsonNode run = runs.get(0);
        assertThat(run.get("startedAtEpochMs").asLong()).isPositive();
        assertThat(run.get("running").asBoolean()).isTrue();
        assertThat(run.get("uncleanExit").asBoolean()).isFalse();
        assertThat(run.get("stoppedAtEpochMs").isNull()).isTrue();
        assertThat(run.get("downForMs").isNull()).isTrue();
        assertThat(run.get("changed")).isEmpty();
        assertThat(run.get("version").asText()).isEqualTo(buildProperties.getVersion());
        assertThat(run.get("branch").asText()).isNotEmpty();
        assertThat(run.get("shortCommitId").asText()).isNotEmpty();
        assertThat(run.get("buildTimeEpochMs").asLong()).isPositive();
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
