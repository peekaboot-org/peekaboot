package org.peekaboot.backend.tracing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.tracing")
public class PeekabootTracingProperties {

    /** Whether the in-memory trace store is created at all. */
    private boolean enabled = true;

    /** Maximum number of traces held in the All bucket, oldest evicted first. */
    private int maxTraces = 1000;

    /** Maximum deduplicated spans retained per trace. */
    private int maxSpansPerTrace = 500;

    /** Maximum traces held in the Errors bucket. */
    private int maxErrorTraces = 100;

    /** Maximum traces held in the Slow bucket. */
    private int maxSlowTraces = 100;

    /** Total end-to-end duration in milliseconds at or above which a trace qualifies for the Slow bucket. */
    private long slowTraceThresholdMs = 1000;

    /** Maximum correlated log entries retained per trace; only populated while the dev toolbar is on. */
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
