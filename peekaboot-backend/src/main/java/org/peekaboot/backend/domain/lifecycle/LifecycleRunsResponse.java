package org.peekaboot.backend.domain.lifecycle;

import java.util.List;

/**
 * The application's start/stop history turned into runs, newest first - one row per time
 * the application started, for as long as this run's own start/stop pair (or its
 * neighbours') survives in the log.
 *
 * <p>A run reports what was actually running, not a diff: {@code version}, {@code branch},
 * {@code shortCommitId} and {@code buildTimeEpochMs} are carried forward from the last
 * start that actually reported them, so every row is self-contained. {@code changed} is
 * the separate, explicit answer to "was this a deployment" - which of {@code "version"},
 * {@code "branch"} and {@code "commit"} differ from the previous run, in that order.
 */
public record LifecycleRunsResponse(List<Run> runs) {

    /**
     * One run of the application, oldest-run-has-no-predecessor aside, always comparable
     * to the run before it.
     *
     * <p>{@code stoppedAtEpochMs} and {@code ranForMs} are both null when the run ended
     * without a matching stop - a crash or a kill - because {@code uncleanExit} is true and
     * we genuinely do not know when it died. {@code downForMs} is the gap to the event
     * immediately before this run's start, but only when that event is a stop - it is null
     * when there is no preceding event at all, or when the preceding event is itself a
     * start, meaning the previous run ended uncleanly and the downtime is unknowable.
     */
    public record Run(
            long startedAtEpochMs,
            Long stoppedAtEpochMs,
            Long ranForMs,
            Long downForMs,
            String version,
            String branch,
            String shortCommitId,
            Long buildTimeEpochMs,
            List<String> changed,
            boolean running,
            boolean uncleanExit) {}
}
