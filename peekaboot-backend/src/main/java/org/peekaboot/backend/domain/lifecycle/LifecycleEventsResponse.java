package org.peekaboot.backend.domain.lifecycle;

import java.util.List;

/**
 * The application's start/stop history for the dashboard, oldest first.
 *
 * <p>A start's build fields are populated only where they differ from the previous
 * start - the diff belongs here rather than in the browser, so "what changed about
 * this deployment" has one definition and one set of tests. The first start in the log
 * carries all of them: there is nothing behind it to differ from.
 */
public record LifecycleEventsResponse(List<Event> events) {

    /** {@code type} is {@code "start"} or {@code "stop"}; every build field may be null. */
    public record Event(
            String type,
            long epochMs,
            String version,
            String branch,
            String commitId,
            String shortCommitId,
            Long buildTimeEpochMs,
            Boolean uncleanPrevious) {}
}
