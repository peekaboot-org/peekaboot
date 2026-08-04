package net.osslabz.peekaboot.backend.tracing.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootTracingPropertiesTest {

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
