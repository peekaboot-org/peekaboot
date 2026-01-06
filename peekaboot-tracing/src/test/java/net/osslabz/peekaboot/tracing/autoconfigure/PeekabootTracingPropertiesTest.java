package net.osslabz.peekaboot.tracing.autoconfigure;

import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties.TraceCaptureMode;
import org.junit.jupiter.api.Test;

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
}
