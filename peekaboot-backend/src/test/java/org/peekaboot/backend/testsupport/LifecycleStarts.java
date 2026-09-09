package org.peekaboot.backend.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;
import org.peekaboot.backend.lifecycle.LifecycleEvent;

/**
 * Builds the START {@link LifecycleEvent} the lifecycle tests replay: pid 1, version
 * {@code 1.0.0} on branch {@code dev} at commit {@code abc1234def}, with no build time,
 * unless a test says otherwise. A stop needs no builder; {@link LifecycleEvent#stop} is it.
 */
public final class LifecycleStarts {

    private LifecycleStarts() {}

    public static StartBuilder start(long epochMs) {
        return new StartBuilder(epochMs);
    }

    public static final class StartBuilder {

        private final long epochMs;
        private long pid = 1;
        private String version = "1.0.0";
        private String buildTime;
        private String branch = "dev";
        private String commit = "abc1234def";

        private StartBuilder(long epochMs) {
            this.epochMs = epochMs;
        }

        public StartBuilder pid(long pid) {
            this.pid = pid;
            return this;
        }

        public StartBuilder version(String version) {
            this.version = version;
            return this;
        }

        /** The build's {@code time} entry, as the epoch millis string the info endpoint carries. */
        public StartBuilder buildTime(String buildTime) {
            this.buildTime = buildTime;
            return this;
        }

        public StartBuilder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public StartBuilder commit(String commit) {
            this.commit = commit;
            return this;
        }

        /** A start from a build without git information: the git map is empty. */
        public StartBuilder withoutGit() {
            this.branch = null;
            this.commit = null;
            return this;
        }

        public LifecycleEvent build() {
            Map<String, String> build = new LinkedHashMap<>();
            build.put("version", version);
            if (buildTime != null) {
                build.put("time", buildTime);
            }
            Map<String, String> git = new LinkedHashMap<>();
            if (branch != null) {
                git.put("branch", branch);
            }
            if (commit != null) {
                git.put("commit.id", commit);
            }
            return LifecycleEvent.start(epochMs, pid, build, git);
        }
    }
}
