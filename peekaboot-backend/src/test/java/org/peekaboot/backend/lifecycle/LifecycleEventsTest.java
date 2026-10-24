package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.LifecycleStarts.start;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleEventsTest {

    private static LifecycleEventsResponse served(List<LifecycleEvent> events) {
        LifecycleEventLog log = new LifecycleEventLog(null);
        log.beginLoad();
        events.forEach(log::recordAndPersist);
        return new LifecycleEvents(log).events();
    }

    /** A line from another writer can carry no info maps at all; serving it must still work. */
    @Test
    void aStartWithoutAnyBuildInformationStillServes() {
        LifecycleEventsResponse response =
                served(List.of(new LifecycleEvent(LifecycleEvent.Type.START, 1_000, 1, null, null)));

        LifecycleEventsResponse.Event event = response.events().get(0);
        assertThat(event.type()).isEqualTo("start");
        assertThat(event.timestamp()).isEqualTo(Instant.ofEpochMilli(1_000));
        assertThat(event.version()).isNull();
        assertThat(event.branch()).isNull();
        assertThat(event.shortCommitId()).isNull();
        assertThat(event.buildTime()).isNull();
    }

    @Test
    void theFirstStartCarriesEverythingBecauseThereIsNothingToCompareItWith() {
        LifecycleEventsResponse response =
                served(List.of(start(1_000).buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event event = response.events().get(0);
        assertThat(event.type()).isEqualTo("start");
        assertThat(event.timestamp()).isEqualTo(Instant.ofEpochMilli(1_000));
        assertThat(event.version()).isEqualTo("1.0.0");
        assertThat(event.branch()).isEqualTo("dev");
        assertThat(event.shortCommitId()).isEqualTo("abc1234");
        assertThat(event.buildTime()).isEqualTo(Instant.ofEpochMilli(1_756_000_000_000L));
        assertThat(event.uncleanPrevious()).isFalse();
    }

    @Test
    void arestartOfTheSameBuildSaysNothingButItsTime() {
        LifecycleEventsResponse response = served(List.of(
                start(1_000).buildTime("1756000000000").build(),
                LifecycleEvent.stop(2_000, 1),
                start(3_000).buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event second = response.events().get(2);
        assertThat(second.timestamp()).isEqualTo(Instant.ofEpochMilli(3_000));
        assertThat(second.version()).isNull();
        assertThat(second.branch()).isNull();
        assertThat(second.shortCommitId()).isNull();
        assertThat(second.buildTime()).isNull();
    }

    @Test
    void onlyWhatChangedIsReported() {
        LifecycleEventsResponse response = served(List.of(
                start(1_000).buildTime("1756000000000").build(),
                start(3_000).branch("feat/x").buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event second = response.events().get(1);
        assertThat(second.branch()).isEqualTo("feat/x");
        assertThat(second.version()).isNull();
        assertThat(second.shortCommitId()).isNull();
    }

    @Test
    void aStartThatFollowsAStartSaysTheLastRunNeverShutDownCleanly() {
        LifecycleEventsResponse response = served(List.of(
                start(1_000).buildTime("1756000000000").build(),
                start(3_000).buildTime("1756000000000").build()));

        assertThat(response.events().get(1).uncleanPrevious()).isTrue();
    }

    @Test
    void aStopCarriesOnlyItsTime() {
        LifecycleEventsResponse response =
                served(List.of(start(1_000).buildTime("1756000000000").build(), LifecycleEvent.stop(2_000, 1)));

        LifecycleEventsResponse.Event stop = response.events().get(1);
        assertThat(stop.type()).isEqualTo("stop");
        assertThat(stop.timestamp()).isEqualTo(Instant.ofEpochMilli(2_000));
        assertThat(stop.version()).isNull();
        assertThat(stop.uncleanPrevious()).isNull();
    }

    @Test
    void aBuildWithoutGitInfoStillReportsItsVersion() {
        LifecycleEvent event = start(1_000).withoutGit().build();

        LifecycleEventsResponse.Event served = served(List.of(event)).events().get(0);

        assertThat(served.version()).isEqualTo("1.0.0");
        assertThat(served.branch()).isNull();
        assertThat(served.shortCommitId()).isNull();
    }
}
