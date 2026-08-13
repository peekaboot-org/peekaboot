package net.osslabz.peekaboot.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiTracingPropertiesTest {

    @Test
    void defaultValues() {
        UiTracingProperties props = new UiTracingProperties();

        assertThat(props.getSlowSpanThresholdMs()).isEqualTo(100L);
        assertThat(props.getVerySlowSpanThresholdMs()).isEqualTo(500L);
        assertThat(props.getSlowQueryThresholdMs()).isEqualTo(50L);
        assertThat(props.getHighQueryCountThreshold()).isEqualTo(5);
        assertThat(props.getHighTraceQueryCountThreshold()).isEqualTo(20);
    }

    @Test
    void customValues() {
        UiTracingProperties props = new UiTracingProperties();
        props.setSlowSpanThresholdMs(200L);
        props.setVerySlowSpanThresholdMs(1000L);
        props.setSlowQueryThresholdMs(100L);
        props.setHighQueryCountThreshold(10);
        props.setHighTraceQueryCountThreshold(50);

        assertThat(props.getSlowSpanThresholdMs()).isEqualTo(200L);
        assertThat(props.getVerySlowSpanThresholdMs()).isEqualTo(1000L);
        assertThat(props.getSlowQueryThresholdMs()).isEqualTo(100L);
        assertThat(props.getHighQueryCountThreshold()).isEqualTo(10);
        assertThat(props.getHighTraceQueryCountThreshold()).isEqualTo(50);
    }
}
