package net.osslabz.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.ui.tracing")
public class UiTracingProperties {

    private long slowSpanThresholdMs = 100;

    private long verySlowSpanThresholdMs = 500;

    private long slowQueryThresholdMs = 50;

    private int highQueryCountThreshold = 5;

    private int highTraceQueryCountThreshold = 20;

    public long getSlowSpanThresholdMs() {
        return slowSpanThresholdMs;
    }

    public void setSlowSpanThresholdMs(long slowSpanThresholdMs) {
        this.slowSpanThresholdMs = slowSpanThresholdMs;
    }

    public long getVerySlowSpanThresholdMs() {
        return verySlowSpanThresholdMs;
    }

    public void setVerySlowSpanThresholdMs(long verySlowSpanThresholdMs) {
        this.verySlowSpanThresholdMs = verySlowSpanThresholdMs;
    }

    public long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    public void setSlowQueryThresholdMs(long slowQueryThresholdMs) {
        this.slowQueryThresholdMs = slowQueryThresholdMs;
    }

    public int getHighQueryCountThreshold() {
        return highQueryCountThreshold;
    }

    public void setHighQueryCountThreshold(int highQueryCountThreshold) {
        this.highQueryCountThreshold = highQueryCountThreshold;
    }

    public int getHighTraceQueryCountThreshold() {
        return highTraceQueryCountThreshold;
    }

    public void setHighTraceQueryCountThreshold(int highTraceQueryCountThreshold) {
        this.highTraceQueryCountThreshold = highTraceQueryCountThreshold;
    }
}
