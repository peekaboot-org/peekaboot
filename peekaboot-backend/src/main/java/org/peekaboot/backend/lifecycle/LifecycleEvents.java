package org.peekaboot.backend.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.peekaboot.backend.domain.lifecycle.LifecycleEventsResponse;

/** Turns the raw log into what the dashboard draws: a time, and only what changed with it. */
public class LifecycleEvents {

    private static final int SHORT_COMMIT_LENGTH = 7;

    private final LifecycleEventLog log;

    public LifecycleEvents(LifecycleEventLog log) {
        this.log = log;
    }

    public LifecycleEventsResponse events() {
        List<LifecycleEventsResponse.Event> served = new ArrayList<>();
        Build previous = null;
        LifecycleEvent.Type previousType = null;
        for (LifecycleEvent event : log.events()) {
            if (event.type() == LifecycleEvent.Type.STOP) {
                served.add(
                        new LifecycleEventsResponse.Event("stop", event.epochMs(), null, null, null, null, null, null));
            } else {
                Build build = Build.of(event);
                served.add(build.changedSince(previous, event.epochMs(), previousType == LifecycleEvent.Type.START));
                previous = build;
            }
            previousType = event.type();
        }
        return new LifecycleEventsResponse(served);
    }

    /**
     * The four fields a marker can show. Read with a fallback each, because an
     * application may carry build info, git info, or both.
     *
     * <p>Package-visible so {@link LifecycleRuns} can read the same raw values off a start
     * without duplicating the fallback rules above.
     */
    record Build(String version, String branch, String commitId, String shortCommitId, Long buildTimeEpochMs) {

        static Build of(LifecycleEvent event) {
            Map<String, String> build = event.build();
            Map<String, String> git = event.git();
            // git-commit-id-maven-plugin's `full` generation mode (used across this
            // project) emits commit.id.full rather than the bare commit.id key.
            String commitId = first(git.get("commit.id"), git.get("commit.id.full"));
            return new Build(
                    first(build.get("version"), git.get("build.version")),
                    git.get("branch"),
                    commitId,
                    shorten(git.get("commit.id.abbrev"), commitId),
                    epochMs(first(build.get("time"), git.get("build.time"))));
        }

        LifecycleEventsResponse.Event changedSince(Build previous, long epochMs, boolean uncleanPrevious) {
            String commit = changed(commitId, previous == null ? null : previous.commitId);
            return new LifecycleEventsResponse.Event(
                    "start",
                    epochMs,
                    changed(version, previous == null ? null : previous.version),
                    changed(branch, previous == null ? null : previous.branch),
                    commit,
                    commit == null ? null : shortCommitId,
                    changed(buildTimeEpochMs, previous == null ? null : previous.buildTimeEpochMs),
                    uncleanPrevious);
        }

        private static <T> T changed(T current, T previous) {
            return Objects.equals(current, previous) ? null : current;
        }

        private static String first(String preferred, String fallback) {
            return preferred != null ? preferred : fallback;
        }

        private static String shorten(String abbreviated, String commitId) {
            if (abbreviated != null) {
                return abbreviated;
            }
            if (commitId == null) {
                return null;
            }
            return commitId.length() > SHORT_COMMIT_LENGTH ? commitId.substring(0, SHORT_COMMIT_LENGTH) : commitId;
        }

        private static Long epochMs(String value) {
            try {
                return value == null ? null : Long.valueOf(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
