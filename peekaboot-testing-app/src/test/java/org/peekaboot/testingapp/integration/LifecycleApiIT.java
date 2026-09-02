package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
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

    @Autowired
    private ObjectProvider<GitProperties> gitProperties;

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
        assertThat(event.path("type").asString()).isEqualTo("start");
        assertThat(event.path("epochMs").asLong()).isPositive();
        assertThat(event.path("version").asString()).isEqualTo(buildProperties.getVersion());
        assertThat(event.path("buildTimeEpochMs").asLong()).isPositive();
        assertThat(event.path("uncleanPrevious").asBoolean()).isFalse();
    }

    /**
     * The pom's {@code failOnNoGitDirectory=false} lets the build run without a resolvable
     * {@code .git} directory, in which case no {@code git.properties} - and no
     * {@code GitProperties} bean - exists and the lifecycle history carries no git facts.
     */
    @Test
    void theRunningApplicationServesTheGitFactsOfItsBuild() {
        GitProperties git = gitProperties.getIfAvailable();
        assumeTrue(git != null, "the build generated no git.properties");

        JsonNode event = awaitFirst("/peekaboot/api/lifecycle/events", "events").get(0);
        JsonNode run = awaitFirst("/peekaboot/api/lifecycle/runs", "runs").get(0);

        assertThat(event.path("branch").asString()).isEqualTo(git.getBranch());
        // the pom's full generation mode writes commit.id.full, a key getCommitId() does not read
        assertThat(event.path("commitId").asString()).isEqualTo(git.get("commit.id.full"));
        assertThat(event.path("shortCommitId").asString()).isEqualTo(git.getShortCommitId());
        assertThat(run.path("branch").asString()).isEqualTo(git.getBranch());
        assertThat(run.path("shortCommitId").asString()).isEqualTo(git.getShortCommitId());
    }

    @Test
    void theRunningApplicationServesItsOwnRunAsTheOldestUnfinishedOne() {
        JsonNode runs = awaitFirst("/peekaboot/api/lifecycle/runs", "runs");

        assertThat(runs).hasSize(1);

        JsonNode run = runs.get(0);
        assertThat(run.path("startedAtEpochMs").asLong()).isPositive();
        assertThat(run.path("running").asBoolean()).isTrue();
        assertThat(run.path("uncleanExit").asBoolean()).isFalse();
        assertThat(run.path("stoppedAtEpochMs").isNull()).isTrue();
        assertThat(run.path("downForMs").isNull()).isTrue();
        assertThat(run.path("changed")).isEmpty();
        assertThat(run.path("version").asString()).isEqualTo(buildProperties.getVersion());
        assertThat(run.path("buildTimeEpochMs").asLong()).isPositive();
    }

    /**
     * The start is recorded through recordWhenLoaded, which hands off to a virtual thread
     * and returns before the event necessarily lands in the log, so the list is polled
     * instead of asserted on the first response.
     */
    private JsonNode awaitFirst(String path, String listField) {
        return await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> api.getJson(path).path(listField), node -> !node.isEmpty());
    }
}
