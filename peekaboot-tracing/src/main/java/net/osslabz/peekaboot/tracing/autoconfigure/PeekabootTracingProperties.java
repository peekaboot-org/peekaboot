package net.osslabz.peekaboot.tracing.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.tracing")
public class PeekabootTracingProperties {

    public enum TraceCaptureMode {
        ERRORS_ONLY,
        ALL
    }

    private boolean enabled = true;

    private int maxTraces = 1000;

    private int maxSpansPerTrace = 100;

    private TraceCaptureMode captureMode;

    private boolean debugToolbar = false;

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

    public TraceCaptureMode getCaptureMode() {
        return captureMode;
    }

    public void setCaptureMode(TraceCaptureMode captureMode) {
        this.captureMode = captureMode;
    }

    public boolean isDebugToolbar() {
        return debugToolbar;
    }

    public void setDebugToolbar(boolean debugToolbar) {
        this.debugToolbar = debugToolbar;
    }

    public TraceCaptureMode getEffectiveCaptureMode() {
        if (captureMode != null) {
            return captureMode;
        }
        return debugToolbar ? TraceCaptureMode.ALL : TraceCaptureMode.ERRORS_ONLY;
    }
}
