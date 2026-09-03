package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.lifecycle.LifecycleRunsResponse;
import org.peekaboot.backend.domain.lifecycle.LifecycleRunsResponse.Run;

class LifecycleRunsTest {

    private static LifecycleEvent start(long epochMs, String version, String branch, String commit) {
        Map<String, String> build = new LinkedHashMap<>();
        build.put("version", version);
        Map<String, String> git = new LinkedHashMap<>();
        git.put("branch", branch);
        git.put("commit.id", commit);
        return LifecycleEvent.start(epochMs, 1, build, git);
    }

    private static final long NOW = 100_000;

    private static LifecycleRunsResponse runsFor(List<LifecycleEvent> events) {
        return runsFor(events, NOW);
    }

    private static LifecycleRunsResponse runsFor(List<LifecycleEvent> events, long nowEpochMs) {
        LifecycleEventLog log = new LifecycleEventLog(null);
        log.beginLoad();
        events.forEach(log::recordAndPersist);
        return new LifecycleRuns(log, () -> nowEpochMs).runs();
    }

    @Test
    void anEmptyLogHasNoRuns() {
        LifecycleRunsResponse response = runsFor(List.of());

        assertThat(response.runs()).isEmpty();
    }

    @Test
    void aSingleStartIsTheCurrentlyRunningRun() {
        LifecycleRunsResponse response = runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234")), 6_000);

        Run run = response.runs().get(0);
        assertThat(run.startedAtEpochMs()).isEqualTo(1_000);
        assertThat(run.running()).isTrue();
        assertThat(run.stoppedAtEpochMs()).isNull();
        assertThat(run.uncleanExit()).isFalse();
        assertThat(run.ranForMs()).isEqualTo(5_000);
        assertThat(run.downForMs()).isNull();
        assertThat(run.changed()).isEmpty();
    }

    @Test
    void aCleanStopEndsTheRunAndStartsTheDowntimeClock() {
        LifecycleRunsResponse response = runsFor(List.of(
                start(1_000, "1.0.0", "dev", "abc1234"),
                LifecycleEvent.stop(4_000, 1),
                start(9_000, "1.0.0", "dev", "abc1234")));

        List<Run> runs = response.runs();
        Run second = runs.get(0);
        Run first = runs.get(1);

        assertThat(first.startedAtEpochMs()).isEqualTo(1_000);
        assertThat(first.stoppedAtEpochMs()).isEqualTo(4_000);
        assertThat(first.ranForMs()).isEqualTo(3_000);
        assertThat(first.running()).isFalse();
        assertThat(first.uncleanExit()).isFalse();

        assertThat(second.startedAtEpochMs()).isEqualTo(9_000);
        assertThat(second.downForMs()).isEqualTo(5_000);
    }

    /**
     * The cap trims oldest-first, so a long-lived application eventually has a log whose
     * first retained event is a stop whose own start fell off the front - not a bug. That
     * stop's timestamp is still real, so the gap to the next start is still knowable.
     */
    @Test
    void aLeadingStopOrphanedByCapTrimmingStillMeasuresTheGapToTheNextStart() {
        LifecycleRunsResponse response =
                runsFor(List.of(LifecycleEvent.stop(500, 1), start(9_000, "1.0.0", "dev", "abc1234")));

        Run run = response.runs().get(0);

        assertThat(run.downForMs()).isEqualTo(8_500);
    }

    @Test
    void backToBackStartsMeanTheFirstRunDiedWithoutRecordingAStop() {
        LifecycleRunsResponse response =
                runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), start(9_000, "1.0.0", "dev", "abc1234")));

        List<Run> runs = response.runs();
        Run second = runs.get(0);
        Run first = runs.get(1);

        assertThat(first.uncleanExit()).isTrue();
        assertThat(first.stoppedAtEpochMs()).isNull();
        assertThat(first.ranForMs()).isNull();
        assertThat(first.running()).isFalse();

        // We know the first run died, but not when - inventing a downtime from the second
        // run's start would be reporting a number nobody measured.
        assertThat(second.downForMs()).isNull();
    }

    @Test
    void aVersionChangeIsReportedAsTheOnlyThingThatChanged() {
        LifecycleRunsResponse response =
                runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), start(9_000, "2.0.0", "dev", "abc1234")));

        List<Run> runs = response.runs();
        Run first = runs.get(1);
        Run second = runs.get(0);

        assertThat(first.version()).isEqualTo("1.0.0");
        assertThat(second.version()).isEqualTo("2.0.0");
        assertThat(second.changed()).containsExactly("version");
    }

    @Test
    void anUnchangedRestartReportsTheCarriedVersionWithoutFlaggingADeployment() {
        LifecycleRunsResponse response =
                runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), start(9_000, "1.0.0", "dev", "abc1234")));

        Run second = response.runs().get(0);

        assertThat(second.version()).isEqualTo("1.0.0");
        assertThat(second.changed()).isEmpty();
    }

    @Test
    void branchAndCommitChangingTogetherAreBothReportedInFixedOrder() {
        LifecycleRunsResponse response =
                runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), start(9_000, "1.0.0", "feat/x", "def5678")));

        Run second = response.runs().get(0);

        assertThat(second.changed()).containsExactly("branch", "commit");
    }

    @Test
    void theOldestRunIsNeverReportedAsADeploymentEvenThoughEverythingAboutItIsNewlyKnown() {
        LifecycleRunsResponse response = runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234")));

        Run oldest = response.runs().get(0);

        assertThat(oldest.changed()).isEmpty();
    }

    @Test
    void aValueMissingFromARawStartIsCarriedForwardFromTheLastRunThatReportedIt() {
        LifecycleEvent noGitInfo = LifecycleEvent.start(9_000, 1, Map.of("version", "1.0.0"), Map.of());

        LifecycleRunsResponse response = runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), noGitInfo));

        Run second = response.runs().get(0);

        assertThat(second.branch()).isEqualTo("dev");
        assertThat(second.shortCommitId()).isEqualTo("abc1234");
        // Nothing in this start's own data differs from what was carried forward, so this
        // is not a deployment.
        assertThat(second.changed()).isEmpty();
    }

    @Test
    void runsAreServedNewestFirst() {
        LifecycleRunsResponse response = runsFor(List.of(
                start(1_000, "1.0.0", "dev", "abc1234"),
                LifecycleEvent.stop(2_000, 1),
                start(3_000, "1.0.0", "dev", "abc1234"),
                LifecycleEvent.stop(4_000, 1),
                start(5_000, "1.0.0", "dev", "abc1234")));

        assertThat(response.runs()).extracting(Run::startedAtEpochMs).containsExactly(5_000L, 3_000L, 1_000L);
    }

    /** Not reachable from a live application - the log always ends on this run's own start. */
    @Test
    void aLogThatEndsOnAStopIsNeverReportedAsStillRunning() {
        LifecycleRunsResponse response =
                runsFor(List.of(start(1_000, "1.0.0", "dev", "abc1234"), LifecycleEvent.stop(4_000, 1)));

        Run run = response.runs().get(0);

        assertThat(run.running()).isFalse();
        assertThat(run.stoppedAtEpochMs()).isEqualTo(4_000);
    }
}
