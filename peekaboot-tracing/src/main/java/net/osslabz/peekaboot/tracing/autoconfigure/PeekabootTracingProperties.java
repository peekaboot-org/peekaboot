package net.osslabz.peekaboot.tracing.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.tracing")
public class PeekabootTracingProperties {

    private boolean enabled = true;

    private int maxTraces = 1000;

    private int maxSpansPerTrace = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTraces() {
        return maxTraces;
    }

    public void setMaxTraces(int maxTraces) {
        this.maxTraces = maxTraces;
    }

    public int getMaxSpansPerTrace() {
        return maxSpansPerTrace;
    }

    public void setMaxSpansPerTrace(int maxSpansPerTrace) {
        this.maxSpansPerTrace = maxSpansPerTrace;
    }
}
