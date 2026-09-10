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
}
