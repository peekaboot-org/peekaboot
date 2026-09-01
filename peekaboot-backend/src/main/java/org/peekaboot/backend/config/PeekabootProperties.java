package org.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot")
public class PeekabootProperties {

    /** The master switch for the dashboard, its API and Peekaboot's own defaults; auto-detected: on for a local launch (IDE, spring-boot:run, bootRun), off otherwise. */
    private boolean enabled = true;

    /**
     * Injects the dev toolbar into HTML responses and turns on correlated-log and full
     * request-detail capture; auto-detected: on for a local launch (IDE, spring-boot:run, bootRun), off otherwise.
     */
    private boolean devToolbar = false;

    /**
     * Gate on unmasked property retrieval, independent of the per-request
     * {@code unmask} query parameter the endpoints that carry property values accept -
     * see PeekabootController. While this is false, unmasked values cannot be obtained
     * by any means, regardless of what the request asks for.
     */
    private boolean enableUnmasking = false;

    private Storage storage = new Storage();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDevToolbar() {
        return devToolbar;
    }

    public void setDevToolbar(boolean devToolbar) {
        this.devToolbar = devToolbar;
    }

    public boolean isEnableUnmasking() {
        return enableUnmasking;
    }

    public void setEnableUnmasking(boolean enableUnmasking) {
        this.enableUnmasking = enableUnmasking;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    /**
     * Where Peekaboot keeps the state it wants to survive a restart, and whether it
     * keeps any at all. The starter switches this on for a local launch and off
     * everywhere else (see PeekabootDefaultsEnvironmentPostProcessor); the {@code false}
     * here is what remains without that detection, so an embedded dev tool that is
     * merely on the classpath still writes nothing to disk.
     */
    public static class Storage {

        /** Whether anything is written to disk at all; auto-detected: on for a local launch (IDE, spring-boot:run, bootRun), off otherwise. */
        private boolean enabled = false;

        /**
         * Where the persisted files live; unset resolves to ${user.home}/.peekaboot/ followed by
         * the application's groupId.artifactId, an explicit value is used verbatim with no
         * per-application subdirectory appended.
         */
        private String dir;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}
