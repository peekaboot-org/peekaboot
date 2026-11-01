package org.peekaboot.backend.tracing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PeekabootTracingPropertiesTest {

    private static PeekabootTracingProperties bind(String threshold) {
        Binder binder = new Binder(
                new MapConfigurationPropertySource(Map.of("peekaboot.tracing.repeated-query-threshold", threshold)));
        return binder.bind("peekaboot.tracing", Bindable.of(PeekabootTracingProperties.class))
                .get();
    }

    /** At 1 every span that ran a query would report it as repeated. */
    @ParameterizedTest
    @ValueSource(ints = {1, 0, -3})
    void refusesARepeatedQueryThresholdBelowTwoWhileBinding(int threshold) {
        assertThatThrownBy(() -> bind(String.valueOf(threshold)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "peekaboot.tracing.repeated-query-threshold must be at least 2 (" + threshold + " configured)");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void bindsARepeatedQueryThresholdFromTwo(int threshold) {
        assertThat(bind(String.valueOf(threshold)).getRepeatedQueryThreshold()).isEqualTo(threshold);
    }
}
