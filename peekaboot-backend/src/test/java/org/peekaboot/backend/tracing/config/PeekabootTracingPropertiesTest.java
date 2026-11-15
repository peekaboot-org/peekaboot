package org.peekaboot.backend.tracing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PeekabootTracingPropertiesTest {

    private static PeekabootTracingProperties bind(String property, String value) {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("peekaboot.tracing." + property, value)));
        return binder.bind("peekaboot.tracing", Bindable.of(PeekabootTracingProperties.class))
                .get();
    }

    /** At 1 every span that ran a query would report it as repeated. */
    @ParameterizedTest
    @ValueSource(ints = {1, 0, -3})
    void refusesARepeatedQueryThresholdBelowTwoWhileBinding(int threshold) {
        assertThatThrownBy(() -> bind("repeated-query-threshold", String.valueOf(threshold)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "peekaboot.tracing.repeated-query-threshold must be at least 2 (" + threshold + " configured)");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void bindsARepeatedQueryThresholdFromTwo(int threshold) {
        assertThat(bind("repeated-query-threshold", String.valueOf(threshold)).getRepeatedQueryThreshold())
                .isEqualTo(threshold);
    }

    /** A negative ring size names nothing, so it fails at binding instead of keeping no line by accident. */
    @ParameterizedTest
    @ValueSource(ints = {-1, -1000})
    void refusesANegativeUntracedLogCapWhileBinding(int cap) {
        assertThatThrownBy(() -> bind("max-untraced-logs", String.valueOf(cap)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("peekaboot.tracing.max-untraced-logs must be at least 0 (" + cap + " configured)");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5000})
    void bindsAnUntracedLogCapFromZero(int cap) {
        assertThat(bind("max-untraced-logs", String.valueOf(cap)).getMaxUntracedLogs())
                .isEqualTo(cap);
    }

    /** Below 1 the All bucket and a trace's spans evict every input as it arrives; below 0 the other caps name nothing. */
    @ParameterizedTest
    @CsvSource({
        "max-traces,0,1",
        "max-traces,-1,1",
        "max-spans-per-trace,0,1",
        "max-error-traces,-1,0",
        "max-slow-traces,-1,0",
        "max-logs-per-trace,-1,0"
    })
    void refusesACapBelowItsFloorWhileBinding(String property, int value, int floor) {
        assertThatThrownBy(() -> bind(property, String.valueOf(value)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "peekaboot.tracing." + property + " must be at least " + floor + " (" + value + " configured)");
    }

    @ParameterizedTest
    @CsvSource({
        "max-traces,1",
        "max-spans-per-trace,1",
        "max-error-traces,0",
        "max-slow-traces,0",
        "max-logs-per-trace,0"
    })
    void bindsACapAtItsFloor(String property, int value) {
        assertThatCode(() -> bind(property, String.valueOf(value))).doesNotThrowAnyException();
    }
}
