package org.peekaboot.backend.lifecycle;

import java.time.Instant;
import java.util.List;

/**
 * The application's start/stop history for the dashboard, oldest first.
 *
 * <p>A start carries every build field it recorded, whatever the start before it ran, so one
 * event reads on its own. Which fields changed between runs is {@link LifecycleRunsResponse}'s
 * {@code changed}.
 */
public record LifecycleEventsResponse(List<Event> events) {

    /** {@code type} is {@code "start"} or {@code "stop"}; every build field may be null. */
    public record Event(
            String type,
            Instant timestamp,
            String version,
            String branch,
            String shortCommitId,
            Instant buildTime,
            Boolean uncleanPrevious) {}
}
