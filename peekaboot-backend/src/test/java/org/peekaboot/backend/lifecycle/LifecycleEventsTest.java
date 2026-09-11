package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.LifecycleStarts.start;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.lifecycle.LifecycleEventsResponse;

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
        assertThat(event.epochMs()).isEqualTo(1_000);
        assertThat(event.version()).isNull();
        assertThat(event.branch()).isNull();
        assertThat(event.commitId()).isNull();
        assertThat(event.buildTimeEpochMs()).isNull();
    }

    @Test
    void theFirstStartCarriesEverythingBecauseThereIsNothingToCompareItWith() {
        LifecycleEventsResponse response =
                served(List.of(start(1_000).buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event event = response.events().get(0);
        assertThat(event.type()).isEqualTo("start");
        assertThat(event.epochMs()).isEqualTo(1_000);
        assertThat(event.version()).isEqualTo("1.0.0");
        assertThat(event.branch()).isEqualTo("dev");
        assertThat(event.commitId()).isEqualTo("abc1234def");
        assertThat(event.shortCommitId()).isEqualTo("abc1234");
        assertThat(event.buildTimeEpochMs()).isEqualTo(1_756_000_000_000L);
        assertThat(event.uncleanPrevious()).isFalse();
    }

    @Test
    void arestartOfTheSameBuildSaysNothingButItsTime() {
        LifecycleEventsResponse response = served(List.of(
                start(1_000).buildTime("1756000000000").build(),
                LifecycleEvent.stop(2_000, 1),
                start(3_000).buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event second = response.events().get(2);
        assertThat(second.epochMs()).isEqualTo(3_000);
        assertThat(second.version()).isNull();
        assertThat(second.branch()).isNull();
        assertThat(second.commitId()).isNull();
        assertThat(second.buildTimeEpochMs()).isNull();
    }

    @Test
    void onlyWhatChangedIsReported() {
        LifecycleEventsResponse response = served(List.of(
                start(1_000).buildTime("1756000000000").build(),
                start(3_000).branch("feat/x").buildTime("1756000000000").build()));

        LifecycleEventsResponse.Event second = response.events().get(1);
        assertThat(second.branch()).isEqualTo("feat/x");
        assertThat(second.version()).isNull();
        assertThat(second.commitId()).isNull();
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
        assertThat(stop.epochMs()).isEqualTo(2_000);
        assertThat(stop.version()).isNull();
        assertThat(stop.uncleanPrevious()).isNull();
    }

    @Test
    void aBuildWithoutGitInfoStillReportsItsVersion() {
        LifecycleEvent event = start(1_000).withoutGit().build();

        LifecycleEventsResponse.Event served = served(List.of(event)).events().get(0);

        assertThat(served.version()).isEqualTo("1.0.0");
        assertThat(served.branch()).isNull();
        assertThat(served.commitId()).isNull();
        assertThat(served.shortCommitId()).isNull();
    }
}
