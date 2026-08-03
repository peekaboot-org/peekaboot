package net.osslabz.peekaboot.backend.tracing.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class PeekabootTracingPropertiesTest {

    @Test
    void getEffectiveCaptureMode_defaultsToErrorsOnly() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode(false));
    }

    @Test
    void getEffectiveCaptureMode_respectsExplicitCaptureMode() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setCaptureMode(TraceCaptureMode.ALL);

        assertEquals(TraceCaptureMode.ALL, props.getEffectiveCaptureMode(false));
    }

    @Test
    void getEffectiveCaptureMode_devToolbarDefaultsToAll() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();

        assertEquals(TraceCaptureMode.ALL, props.getEffectiveCaptureMode(true));
    }

    @Test
    void getEffectiveCaptureMode_explicitModeOverridesDevToolbar() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setCaptureMode(TraceCaptureMode.ERRORS_ONLY);

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode(true));
    }

    @Test
    void getEffectiveCaptureMode_devToolbarFalseKeepsDefault() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode(false));
    }

    @Test
    void maxErrorTracesDefaultsTo100() {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        assertThat(properties.getMaxErrorTraces()).isEqualTo(100);
    }

    @Test
    void maxSlowTracesDefaultsTo100() {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        assertThat(properties.getMaxSlowTraces()).isEqualTo(100);
    }

    @Test
    void slowTraceThresholdMsDefaultsTo1000() {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        assertThat(properties.getSlowTraceThresholdMs()).isEqualTo(1000L);
    }

    @Test
    void newPropertiesAreSettable() {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        properties.setMaxErrorTraces(5);
        properties.setMaxSlowTraces(7);
        properties.setSlowTraceThresholdMs(250L);
        assertThat(properties.getMaxErrorTraces()).isEqualTo(5);
        assertThat(properties.getMaxSlowTraces()).isEqualTo(7);
        assertThat(properties.getSlowTraceThresholdMs()).isEqualTo(250L);
    }
}
