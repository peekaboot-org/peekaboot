package org.peekaboot.backend.lifecycle;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * The build a start event reports, read with a fallback each because an application may
 * carry build info, git info, or both. Both projections over the log ({@link LifecycleEvents}
 * and {@link LifecycleRuns}) read their fields off this one record.
 */
record BuildFacts(String version, String branch, String commitId, String shortCommitId, Instant buildTime) {

    /** No build reported at all: what the first start is compared against. */
    static final BuildFacts NONE = new BuildFacts(null, null, null, null, null);

    private static final int SHORT_COMMIT_LENGTH = 7;

    private static final String BUILD_VERSION = "version";
    private static final String BUILD_TIME = "time";
    private static final String GIT_BRANCH = "branch";
    private static final String GIT_COMMIT_ID = "commit.id";
    // With git-commit-id's commitIdGenerationMode=full (the testing-app's setting)
    // the key is commit.id.full, not commit.id.
    private static final String GIT_COMMIT_ID_FULL = "commit.id.full";
    private static final String GIT_COMMIT_ID_ABBREV = "commit.id.abbrev";
    private static final String GIT_BUILD_VERSION = "build.version";
    private static final String GIT_BUILD_TIME = "build.time";

    /** Every key {@link #of} reads, from both sources; {@link InfoEntries} keeps these and no others. */
    static final Set<String> READ_KEYS = Set.of(
            BUILD_VERSION,
            BUILD_TIME,
            GIT_BRANCH,
            GIT_COMMIT_ID,
            GIT_COMMIT_ID_FULL,
            GIT_COMMIT_ID_ABBREV,
            GIT_BUILD_VERSION,
            GIT_BUILD_TIME);

    static BuildFacts of(LifecycleEvent event) {
        Map<String, String> build = event.build();
        Map<String, String> git = event.git();
        String commitId = first(git.get(GIT_COMMIT_ID), git.get(GIT_COMMIT_ID_FULL));
        return new BuildFacts(
                first(build.get(BUILD_VERSION), git.get(GIT_BUILD_VERSION)),
                git.get(GIT_BRANCH),
                commitId,
                shorten(git.get(GIT_COMMIT_ID_ABBREV), commitId),
                instant(first(build.get(BUILD_TIME), git.get(GIT_BUILD_TIME))));
    }

    /**
     * These facts, with every field this start does not report taken from {@code fallback}:
     * missing build or git info, a hand-edited file, a run that predates this tracking. The
     * field never stopped being true. A null {@code fallback} leaves the facts as they are.
     */
    BuildFacts withFallback(BuildFacts fallback) {
        if (fallback == null) {
            return this;
        }
        return new BuildFacts(
                first(version, fallback.version),
                first(branch, fallback.branch),
                first(commitId, fallback.commitId),
                first(shortCommitId, fallback.shortCommitId),
                first(buildTime, fallback.buildTime));
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

    /** The log stores a build time as epoch millis. */
    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
