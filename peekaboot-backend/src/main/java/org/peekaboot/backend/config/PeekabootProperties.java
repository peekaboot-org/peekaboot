package org.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot")
public class PeekabootProperties {

    /**
     * The master switch for the dashboard, its API and Peekaboot's own defaults: false
     * unless PeekabootDefaultsEnvironmentPostProcessor's launch-context detection sets it
     * (on for a local launch from an IDE, spring-boot:run or bootRun), and any explicit
     * setting wins over that detection.
     */
    private boolean enabled = false;

    /**
     * Injects the dev toolbar into HTML responses and turns on correlated-log and full
     * request-detail capture. Defaulted from the launch context like {@code peekaboot.enabled}.
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

    private Lifecycle lifecycle = new Lifecycle();

    private Security security = new Security();

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

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    /**
     * Where Peekaboot keeps the state it wants to survive a restart, and whether it
     * keeps any at all. The {@code false} is what remains without the launch-context
     * detection, so an embedded dev tool that is merely on the classpath still writes
     * nothing to disk.
     */
    public static class Storage {

        /** Whether anything is written to disk at all. Defaulted from the launch context like {@code peekaboot.enabled}. */
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

    /**
     * The switch PeekabootLifecycleAutoConfiguration reads through its condition before
     * any Peekaboot bean exists; bound here as well so it carries configuration metadata
     * and appears on the dashboard's own Config tab.
     */
    public static class Lifecycle {

        /** Enables the application-ready startup summary, the matching shutdown summary and the run history behind the Lifecycle tab. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * The credentials Peekaboot falls back to when nothing else authenticates
     * {@code /peekaboot/**}. Everything here is unset by default; {@code enabled} is
     * defaulted from the launch context like {@code peekaboot.enabled}, and on only for a
     * deployment launch.
     */
    public static class Security {

        /** Whether Peekaboot challenges dashboard requests that reach it unauthenticated. */
        private boolean enabled = false;

        /** Unset derives {@code <artifact>-admin} from the build coordinates or the application name. */
        private String username;

        /**
         * Unset generates a password on first start and persists its hash. An explicit value
         * is never written to disk.
         */
        private String password;

        /** Unset resolves to {@code security.properties} beside Peekaboot's other state. */
        private String credentialsFile;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getCredentialsFile() {
            return credentialsFile;
        }

        public void setCredentialsFile(String credentialsFile) {
            this.credentialsFile = credentialsFile;
        }
    }
}
