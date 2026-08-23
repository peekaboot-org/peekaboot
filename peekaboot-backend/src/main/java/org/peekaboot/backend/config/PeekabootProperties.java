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
}
