package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import tools.jackson.databind.JsonNode;

/**
 * Proves the lifecycle history - the raw events and the runs derived from them - is
 * actually reachable through the real, auto-configured app. The app runs with storage
 * disabled (the default for this module), so this exercises the in-memory path: the
 * current run's own start, recorded asynchronously off {@code ApplicationReadyEvent} (see
 * LifecycleEventRecorder.onReady), is served without any storage configuration at all.
 *
 * <p>Fields are read with {@code required} rather than {@code path}: the {@code MissingNode}
 * {@code path} yields for an absent field iterates empty and reads as {@code false}, so a
 * field the API stopped serving would slip past {@code isEmpty()} and {@code isFalse()}.
 */
class LifecycleApiIT extends TestingAppITBase {

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private ObjectProvider<GitProperties> gitProperties;

    @Test
    void theRunningApplicationServesItsOwnStartWithEveryBuildFieldItKnows() {
        JsonNode events = awaitFirst("/peekaboot/api/lifecycle/events", "events");

        assertThat(events).hasSize(1);

        JsonNode event = events.get(0);
        assertThat(event.required("type").asString()).isEqualTo("start");
        assertThat(Instant.parse(event.required("timestamp").asString())).isAfter(Instant.EPOCH);
        assertThat(event.required("version").asString()).isEqualTo(buildProperties.getVersion());
        assertThat(Instant.parse(event.required("buildTime").asString())).isAfter(Instant.EPOCH);
        assertThat(event.required("uncleanPrevious").asBoolean()).isFalse();
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

        assertThat(event.required("branch").asString()).isEqualTo(git.getBranch());
        assertThat(event.required("shortCommitId").asString()).isEqualTo(git.getShortCommitId());
        assertThat(run.required("branch").asString()).isEqualTo(git.getBranch());
        assertThat(run.required("shortCommitId").asString()).isEqualTo(git.getShortCommitId());
    }

    @Test
    void theRunningApplicationServesItsOwnRunAsTheOldestUnfinishedOne() {
        JsonNode runs = awaitFirst("/peekaboot/api/lifecycle/runs", "runs");

        assertThat(runs).hasSize(1);

        JsonNode run = runs.get(0);
        assertThat(Instant.parse(run.required("startedAt").asString())).isAfter(Instant.EPOCH);
        assertThat(run.required("running").asBoolean()).isTrue();
        assertThat(run.required("uncleanExit").asBoolean()).isFalse();
        assertThat(run.required("stoppedAt").isNull()).isTrue();
        assertThat(run.required("downForMs").isNull()).isTrue();
        assertThat(run.required("changed")).isEmpty();
        assertThat(run.required("version").asString()).isEqualTo(buildProperties.getVersion());
        assertThat(Instant.parse(run.required("buildTime").asString())).isAfter(Instant.EPOCH);
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
