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
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Proves the lifecycle history - the raw events and the runs derived from them - is
 * actually reachable through the real, auto-configured app. The app runs with storage
 * disabled (the default for this module), so this exercises the in-memory path: the
 * current run's own start, recorded asynchronously off {@code ApplicationReadyEvent} (see
 * LifecycleEventRecorder.onReady), is served without any storage configuration at all.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LifecycleApiIT {

    @LocalServerPort
    private int port;

    @Autowired
    private BuildProperties buildProperties;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void theRunningApplicationServesItsOwnStartWithEveryBuildFieldItKnows() {
        JsonNode events = awaitFirst("/peekaboot/api/lifecycle/events", "events");

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

    @Test
    void theRunningApplicationServesItsOwnRunAsTheOldestUnfinishedOne() {
        JsonNode runs = awaitFirst("/peekaboot/api/lifecycle/runs", "runs");

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

    /**
     * The start is recorded through recordWhenLoaded, which hands off to a virtual thread
     * and returns before the event necessarily lands in the log, so the list is polled
     * instead of asserted on the first response.
     */
    private JsonNode awaitFirst(String path, String listField) {
        return await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> api.getJson(path).get(listField), node -> !node.isEmpty());
    }
}
