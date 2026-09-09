package org.peekaboot.backend.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.peekaboot.backend.domain.lifecycle.LifecycleEventsResponse;

/** Turns the raw log into what the dashboard draws: a time, and only what changed with it. */
public class LifecycleEvents {

    private final LifecycleEventLog log;

    public LifecycleEvents(LifecycleEventLog log) {
        this.log = log;
    }

    public LifecycleEventsResponse events() {
        List<LifecycleEventsResponse.Event> served = new ArrayList<>();
        BuildFacts previous = null;
        LifecycleEvent.Type previousType = null;
        for (LifecycleEvent event : log.events()) {
            if (event.type() == LifecycleEvent.Type.STOP) {
                served.add(
                        new LifecycleEventsResponse.Event("stop", event.epochMs(), null, null, null, null, null, null));
            } else {
                BuildFacts build = BuildFacts.of(event);
                served.add(changedSince(build, previous, event.epochMs(), previousType == LifecycleEvent.Type.START));
                previous = build;
            }
            previousType = event.type();
        }
        return new LifecycleEventsResponse(served);
    }

    /** A marker says only what is new: a field equal to the previous start's is left null. */
    private static LifecycleEventsResponse.Event changedSince(
            BuildFacts current, BuildFacts previous, long epochMs, boolean uncleanPrevious) {
        String commit = changed(current.commitId(), previous == null ? null : previous.commitId());
        return new LifecycleEventsResponse.Event(
                "start",
                epochMs,
                changed(current.version(), previous == null ? null : previous.version()),
                changed(current.branch(), previous == null ? null : previous.branch()),
                commit,
                commit == null ? null : current.shortCommitId(),
                changed(current.buildTimeEpochMs(), previous == null ? null : previous.buildTimeEpochMs()),
                uncleanPrevious);
    }

    private static <T> T changed(T current, T previous) {
        return Objects.equals(current, previous) ? null : current;
    }
}
