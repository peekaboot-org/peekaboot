package org.peekaboot.backend.lifecycle;

import java.util.Map;

/**
 * The build a start event reports, read with a fallback each because an application may
 * carry build info, git info, or both. Both projections over the log ({@link LifecycleEvents}
 * and {@link LifecycleRuns}) read their fields off this one record.
 */
record BuildFacts(String version, String branch, String commitId, String shortCommitId, Long buildTimeEpochMs) {

    private static final int SHORT_COMMIT_LENGTH = 7;

    static BuildFacts of(LifecycleEvent event) {
        Map<String, String> build = event.build();
        Map<String, String> git = event.git();
        // With git-commit-id's commitIdGenerationMode=full (the testing-app's setting)
        // the key is commit.id.full, not commit.id.
        String commitId = first(git.get("commit.id"), git.get("commit.id.full"));
        return new BuildFacts(
                first(build.get("version"), git.get("build.version")),
                git.get("branch"),
                commitId,
                shorten(git.get("commit.id.abbrev"), commitId),
                epochMs(first(build.get("time"), git.get("build.time"))));
    }

    /**
     * These facts, with every field this start does not report taken from {@code fallback}:
     * missing build or git info, a hand-edited file, a run that predates this tracking. The
     * field never stopped being true. A null {@code fallback} leaves the facts as they are.
     */
    BuildFacts orElse(BuildFacts fallback) {
        if (fallback == null) {
            return this;
        }
        return new BuildFacts(
                first(version, fallback.version),
                first(branch, fallback.branch),
                first(commitId, fallback.commitId),
                first(shortCommitId, fallback.shortCommitId),
                first(buildTimeEpochMs, fallback.buildTimeEpochMs));
    }

    private static <T> T first(T preferred, T fallback) {
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
