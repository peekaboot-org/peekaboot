package net.osslabz.peekaboot.backend.tracing.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.tracing")
public class PeekabootTracingProperties {

    private boolean enabled = true;

    private int maxTraces = 1000;

    private int maxSpansPerTrace = 100;

    private int maxErrorTraces = 100;

    private int maxSlowTraces = 100;

    private long slowTraceThresholdMs = 1000;

    private int maxLogsPerTrace = 500;

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

    public int getMaxErrorTraces() {
        return maxErrorTraces;
    }

    public void setMaxErrorTraces(int maxErrorTraces) {
        this.maxErrorTraces = maxErrorTraces;
    }

    public int getMaxSlowTraces() {
        return maxSlowTraces;
    }

    public void setMaxSlowTraces(int maxSlowTraces) {
        this.maxSlowTraces = maxSlowTraces;
    }

    public long getSlowTraceThresholdMs() {
        return slowTraceThresholdMs;
    }

    public void setSlowTraceThresholdMs(long slowTraceThresholdMs) {
        this.slowTraceThresholdMs = slowTraceThresholdMs;
    }

    public int getMaxLogsPerTrace() {
        return maxLogsPerTrace;
    }

    public void setMaxLogsPerTrace(int maxLogsPerTrace) {
        this.maxLogsPerTrace = maxLogsPerTrace;
    }
}
