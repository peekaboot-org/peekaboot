package net.osslabz.peekaboot.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiTracingPropertiesTest {

    @Test
    void defaultValues() {
        UiTracingProperties props = new UiTracingProperties();

        assertEquals(100L, props.getSlowSpanThresholdMs());
        assertEquals(500L, props.getVerySlowSpanThresholdMs());
        assertEquals(50L, props.getSlowQueryThresholdMs());
        assertEquals(5, props.getHighQueryCountThreshold());
        assertEquals(20, props.getHighTraceQueryCountThreshold());
    }

    @Test
    void customValues() {
        UiTracingProperties props = new UiTracingProperties();
        props.setSlowSpanThresholdMs(200L);
        props.setVerySlowSpanThresholdMs(1000L);
        props.setSlowQueryThresholdMs(100L);
        props.setHighQueryCountThreshold(10);
        props.setHighTraceQueryCountThreshold(50);

        assertEquals(200L, props.getSlowSpanThresholdMs());
        assertEquals(1000L, props.getVerySlowSpanThresholdMs());
        assertEquals(100L, props.getSlowQueryThresholdMs());
        assertEquals(10, props.getHighQueryCountThreshold());
        assertEquals(50, props.getHighTraceQueryCountThreshold());
    }
}
