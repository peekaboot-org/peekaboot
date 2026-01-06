package net.osslabz.peekaboot.tracing.autoconfigure;

import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PeekabootTracingPropertiesTest {

    @Test
    void getEffectiveCaptureMode_defaultsToErrorsOnly() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode());
    }

    @Test
    void getEffectiveCaptureMode_respectsExplicitCaptureMode() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setCaptureMode(TraceCaptureMode.ALL);

        assertEquals(TraceCaptureMode.ALL, props.getEffectiveCaptureMode());
    }

    @Test
    void getEffectiveCaptureMode_debugToolbarDefaultsToAll() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setDebugToolbar(true);

        assertEquals(TraceCaptureMode.ALL, props.getEffectiveCaptureMode());
    }

    @Test
    void getEffectiveCaptureMode_explicitModeOverridesDebugToolbar() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setDebugToolbar(true);
        props.setCaptureMode(TraceCaptureMode.ERRORS_ONLY);

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode());
    }

    @Test
    void getEffectiveCaptureMode_debugToolbarFalseKeepsDefault() {
        PeekabootTracingProperties props = new PeekabootTracingProperties();
        props.setDebugToolbar(false);

        assertEquals(TraceCaptureMode.ERRORS_ONLY, props.getEffectiveCaptureMode());
    }
}
