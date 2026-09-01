package org.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot")
public class PeekabootProperties {

    private boolean enabled = true;

    private boolean devToolbar = false;

    /**
     * Gate on unmasked property retrieval, independent of the per-request
     * {@code unmask} query parameter the endpoints that carry property values accept -
     * see PeekabootController. While this is false, unmasked values cannot be obtained
     * by any means, regardless of what the request asks for.
     */
    private boolean enableUnmasking = false;

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

    private Storage storage = new Storage();

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    /**
     * Where Peekaboot keeps the state it wants to survive a restart, and whether it
     * keeps any at all. Off by default: an embedded dev tool writes nothing to a
     * developer's disk until asked.
     */
    public static class Storage {

        private boolean enabled = false;

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
