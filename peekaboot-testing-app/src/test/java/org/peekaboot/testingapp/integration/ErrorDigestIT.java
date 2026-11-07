package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.DigestFixtureController;
import org.peekaboot.fixtures.OutcomeFixtureController;
import tools.jackson.databind.JsonNode;

/**
 * The error digest over real HTTP. The digest fixture's route is this class's alone, so its entry
 * counts only this class's requests; each test compares against the count it read first.
 */
class ErrorDigestIT extends TestingAppITBase {

    private static final String ROUTE = DigestFixtureController.PATH;

    /** The fixture's entry, or a missing node before its first failure. */
    private JsonNode fixtureEntry() {
        return api.getJson("/peekaboot/api/errors?route={route}", ROUTE)
                .path("errors")
                .path(0);
    }

    /** Fails the fixture with a fresh tag and returns the request's trace id. */
    private String fail(String tag) {
        return traces.get(ROUTE + "?tag=" + tag);
    }

    /** The digest records a trace once its root is in the store, so the entry is awaited, never the trace alone. */
    private JsonNode awaitFixtureEntryCounting(int count) {
        return await().atMost(Duration.ofSeconds(15))
                .until(this::fixtureEntry, entry -> entry.path("count").asInt() == count);
    }

    @Test
    void twoFailuresOfOneKindAreOneEntryNamingTheLatest() {
        int before = fixtureEntry().path("count").asInt(0);
        String lastTag = UUID.randomUUID().toString();
        fail(UUID.randomUUID().toString());
        String last = fail(lastTag);

        JsonNode entry = awaitFixtureEntryCounting(before + 2);
        JsonNode trace = traces.awaitTrace(last, TraceApiClient.COMPLETE);

        assertThat(entry.path("type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(entry.path("route").asString()).isEqualTo(ROUTE);
        assertThat(trace.path("summary").path("cause").path("applicationFrame").asString())
                .as("the trace's own frame keeps its line")
                .startsWith("org.peekaboot.testingapp.DigestFailure.failure(DigestFailure.java:");
        assertThat(entry.path("applicationFrame").asString())
                .isEqualTo("org.peekaboot.testingapp.DigestFailure.failure(DigestFailure.java)");
        assertThat(entry.path("lastTraceId").asString()).isEqualTo(last);
        assertThat(entry.path("lastMessage").asString()).isEqualTo("digest fixture " + lastTag);
        assertThat(entry.path("traceAvailable").asBoolean()).isTrue();
        assertThat(entry.path("runs").path(entry.path("runs").size() - 1).asString())
                .isEqualTo(trace.path("summary").path("run").asString());
        assertThat(Instant.parse(entry.path("firstSeen").asString()))
                .isBeforeOrEqualTo(Instant.parse(entry.path("lastSeen").asString()));
    }

    @Test
    void sinceLeavesOutAnEntryLastSeenBeforeIt() {
        int before = fixtureEntry().path("count").asInt(0);
        fail(UUID.randomUUID().toString());
        Instant lastSeen = Instant.parse(
                awaitFixtureEntryCounting(before + 1).path("lastSeen").asString());

        JsonNode atLastSeen = api.getJson("/peekaboot/api/errors?route={route}&since={since}", ROUTE, lastSeen);
        JsonNode afterIt =
                api.getJson("/peekaboot/api/errors?route={route}&since={since}", ROUTE, lastSeen.plusMillis(1));

        assertThat(atLastSeen.path("errors")).hasSize(1);
        assertThat(afterIt.path("errors")).isEmpty();
    }

    /** A 5xx without an exception names no class and no frame; the fields are null, never absent. */
    @Test
    void aFailureWithoutAnExceptionServesItsTypeAndFrameAsNull() {
        String statusRoute = "/fixtures/status/{status}";
        traces.get(OutcomeFixtureController.statusPath(503));

        JsonNode entry = await().atMost(Duration.ofSeconds(15))
                .until(
                        () -> api.getJson("/peekaboot/api/errors?route={route}", statusRoute)
                                .path("errors")
                                .path(0),
                        found -> !found.isMissingNode());

        assertThat(entry.has("type")).isTrue();
        assertThat(entry.path("type").isNull()).isTrue();
        assertThat(entry.path("applicationFrame").isNull()).isTrue();
        assertThat(entry.path("route").asString()).isEqualTo(statusRoute);
    }
}
