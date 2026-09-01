package org.peekaboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.ui.tracing")
public class UiTracingProperties {

    /** A single span's own duration in milliseconds at or above which it gets the SLOW issue. */
    private long slowSpanThresholdMs = 100;

    /** A single span's own duration in milliseconds at or above which it gets VERY_SLOW instead of SLOW. */
    private long verySlowSpanThresholdMs = 500;

    /** A database query span's duration in milliseconds at or above which it gets the SLOW_QUERY issue. */
    private long slowQueryThresholdMs = 50;

    /** Direct database-query children a single span may have before it gets HIGH_QUERY_COUNT. */
    private int highQueryCountThreshold = 5;

    /**
     * Total database queries a whole trace may run before it gets HIGH_QUERY_COUNT, even if no
     * single span crosses the per-span threshold.
     */
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
