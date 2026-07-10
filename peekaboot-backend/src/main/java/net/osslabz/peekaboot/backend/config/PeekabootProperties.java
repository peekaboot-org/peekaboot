package net.osslabz.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot")
public class PeekabootProperties {

    private boolean enabled = true;

    private boolean devToolbar = false;

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
}
