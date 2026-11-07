package org.peekaboot.backend.lifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Turns the raw log into what the dashboard draws: every start with the build it ran, and every stop. */
public class LifecycleEvents {

    private final LifecycleEventLog eventLog;

    public LifecycleEvents(LifecycleEventLog eventLog) {
        this.eventLog = eventLog;
    }

    public LifecycleEventsResponse events() {
        List<LifecycleEventsResponse.Event> served = new ArrayList<>();
        LifecycleEvent.Type previousType = null;
        for (LifecycleEvent event : eventLog.events()) {
            Instant timestamp = Instant.ofEpochMilli(event.epochMs());
            if (event.type() == LifecycleEvent.Type.STOP) {
                served.add(new LifecycleEventsResponse.Event("stop", timestamp, null, null, null, null, null));
            } else {
                BuildFacts build = BuildFacts.of(event);
                served.add(new LifecycleEventsResponse.Event(
                        "start",
                        timestamp,
                        build.version(),
                        build.branch(),
                        build.shortCommitId(),
                        build.buildTime(),
                        previousType == LifecycleEvent.Type.START));
            }
            previousType = event.type();
        }
        return new LifecycleEventsResponse(served);
    }
}
