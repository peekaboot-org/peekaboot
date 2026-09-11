package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.environment.PropertyValue;
import org.peekaboot.backend.masking.MaskingEngine;

class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = new EnvironmentMapper(new MaskingEngine());

    @Test
    void map_shouldExtractActiveProfiles() {
        EnvResponse env = new EnvResponse(List.of("dev", "local"), List.of());
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).containsExactly("dev", "local");
    }

    /** The endpoint types values as it finds them (a YAML port is an Integer); the dashboard shows text. */
    @Test
    void map_shouldExtractPropertySources() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of(
                                "server.port",
                                new EnvResponse.PropertyValue(
                                        8080, "class path resource [application.properties] - 3:14")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("application.properties");
        assertThat(result.propertySources().get(0).properties())
                .extracting(PropertyValue::key, PropertyValue::value, PropertyValue::origin)
                .containsExactly(tuple("server.port", "8080", "class path resource [application.properties] - 3:14"));
    }

    @Test
    void map_shouldHandleNullInput() {
        EnvironmentInfo result = mapper.map(null, false);
        assertThat(result.activeProfiles()).isEmpty();
        assertThat(result.propertySources()).isEmpty();
    }

    @Test
    void map_shouldHandleEmptyProfiles() {
        EnvResponse env = new EnvResponse(List.of(), List.of());
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).isEmpty();
    }

    @Test
    void map_shouldFallBackToUnknownNameWhenPropertySourceNameIsNull() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        null, Map.of("server.port", new EnvResponse.PropertyValue("8080", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("unknown");
    }

    @Test
    void map_shouldReturnNullValueWhenPropertyValueIsNull() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("some.flag", new EnvResponse.PropertyValue(null, "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isNull();
    }

    @Test
    void map_shouldMaskSensitiveKeyValue() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of(
                                "spring.datasource.password",
                                new EnvResponse.PropertyValue("hunter2", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("******");
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of(
                                "spring.datasource.password",
                                new EnvResponse.PropertyValue("hunter2", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, true);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("hunter2");
    }
}
