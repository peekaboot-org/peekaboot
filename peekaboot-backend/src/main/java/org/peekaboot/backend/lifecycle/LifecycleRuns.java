package org.peekaboot.backend.lifecycle;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Turns the raw log into what the dashboard tabulates: one row per start, how long it
 * ran, how long the application was down before it, and whether it was a deployment.
 *
 * <p>This is the second projection over {@link LifecycleEventLog}, alongside
 * {@link LifecycleEvents}. Where that one nulls out a field the moment it repeats a
 * previous start - the right shape for a chart marker that only wants to say what's new -
 * a run has to stand on its own: it carries the last known value forward so every row
 * reports what was actually running, and reports separately, via {@code changed}, whether
 * this run was a deployment.
 */
public class LifecycleRuns {

    private final LifecycleEventLog eventLog;
    private final Clock clock;

    public LifecycleRuns(LifecycleEventLog eventLog) {
        this(eventLog, Clock.systemUTC());
    }

    /** {@code clock} supplies the instant a still-running run is measured up to; tests fix it. */
    LifecycleRuns(LifecycleEventLog eventLog, Clock clock) {
        this.eventLog = eventLog;
        this.clock = clock;
    }

    public LifecycleRunsResponse runs() {
        List<LifecycleEvent> events = eventLog.events();
        List<LifecycleRunsResponse.Run> runs = new ArrayList<>();
        BuildFacts carried = null;
        long nowEpochMs = clock.millis();
        for (int i = 0; i < events.size(); i++) {
            LifecycleEvent event = events.get(i);
            if (event.type() != LifecycleEvent.Type.START) {
                continue;
            }
            BuildFacts effective = BuildFacts.of(event).withFallback(carried);
            LifecycleEvent previous = i == 0 ? null : events.get(i - 1);
            LifecycleEvent next = i + 1 < events.size() ? events.get(i + 1) : null;
            runs.add(toRun(event, previous, next, effective, changed(effective, carried), nowEpochMs));
            carried = effective;
        }
        Collections.reverse(runs);
        return new LifecycleRunsResponse(runs);
    }

    /**
     * The oldest run has no predecessor to differ from, so it is never a deployment - only
     * a run that replaced a known previous one can be.
     */
    private static List<String> changed(BuildFacts current, BuildFacts previous) {
        if (previous == null) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "version", current.version(), previous.version());
        addIfChanged(changed, "branch", current.branch(), previous.branch());
        addIfChanged(changed, "commit", current.commitId(), previous.commitId());
        return List.copyOf(changed);
    }

    private static void addIfChanged(List<String> changed, String label, Object current, Object previous) {
        if (!Objects.equals(current, previous)) {
            changed.add(label);
        }
    }

    private static LifecycleRunsResponse.Run toRun(
            LifecycleEvent start,
            LifecycleEvent previous,
            LifecycleEvent next,
            BuildFacts effective,
            List<String> changed,
            long nowEpochMs) {
        Timing timing = Timing.of(start, next, nowEpochMs);
        Long downForMs = downForMs(start, previous);
        return new LifecycleRunsResponse.Run(
                Instant.ofEpochMilli(start.epochMs()),
                timing.stoppedAt(),
                timing.ranForMs(),
                downForMs,
                effective.version(),
                effective.branch(),
                effective.shortCommitId(),
                effective.buildTime(),
                changed,
                timing.running(),
                timing.uncleanExit());
    }

    /**
     * The gap before a run is only knowable when the preceding event is a stop the log
     * actually recorded: no preceding event at all, or one that is itself a start - the
     * previous run ended uncleanly - leaves no stop to measure from. A stop that opens the
     * retained log because the cap trimmed its own start still carries a real timestamp,
     * so the gap to the next start is still genuinely knowable and reported, not null.
     */
    private static Long downForMs(LifecycleEvent start, LifecycleEvent previous) {
        if (previous == null || previous.type() != LifecycleEvent.Type.STOP) {
            return null;
        }
        return start.epochMs() - previous.epochMs();
    }

    /** How a run ended (or hasn't), derived from the event that follows its start. */
    private record Timing(Instant stoppedAt, Long ranForMs, boolean running, boolean uncleanExit) {

        private static Timing of(LifecycleEvent start, LifecycleEvent next, long nowEpochMs) {
            if (next == null) {
                long ranForMs = Math.max(0, nowEpochMs - start.epochMs());
                return new Timing(null, ranForMs, true, false);
            }
            if (next.type() == LifecycleEvent.Type.STOP) {
                return new Timing(Instant.ofEpochMilli(next.epochMs()), next.epochMs() - start.epochMs(), false, false);
            }
            return new Timing(null, null, false, true);
        }
    }
}
