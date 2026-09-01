package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;

class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = new EnvironmentMapper();

    @Test
    void map_shouldExtractActiveProfiles() {
        EnvResponse env = new EnvResponse(List.of("dev", "local"), List.of());
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).containsExactly("dev", "local");
    }

    @Test
    void map_shouldExtractPropertySources() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("server.port", new EnvResponse.PropertyValue("8080", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("application.properties");
        assertThat(result.propertySources().get(0).properties()).hasSize(1);
        assertThat(result.propertySources().get(0).properties().get(0).key()).isEqualTo("server.port");
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("8080");
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
    void map_shouldHandleNullProfiles() {
        EnvResponse env = new EnvResponse(null, null);
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).isEmpty();
        assertThat(result.propertySources()).isEmpty();
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
    void map_shouldReturnEmptyPropertiesWhenSourcePropertiesIsNull() {
        EnvResponse env =
                new EnvResponse(List.of(), List.of(new EnvResponse.PropertySource("application.properties", null)));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).properties()).isEmpty();
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
    void map_shouldNotMaskInnocuousKeyValue() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("server.port", new EnvResponse.PropertyValue("8080", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("8080");
    }

    @Test
    void map_shouldNotMaskNegativeCaseKeysThatMerelyContainKey() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of(
                                "spring.jpa.key-generator",
                                        new EnvResponse.PropertyValue("sequence", "application.properties"),
                                "server.ssl.key-store",
                                        new EnvResponse.PropertyValue(
                                                "classpath:keystore.p12", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties())
                .anyMatch(p ->
                        p.key().equals("spring.jpa.key-generator") && p.value().equals("sequence"))
                .anyMatch(
                        p -> p.key().equals("server.ssl.key-store") && p.value().equals("classpath:keystore.p12"));
    }

    @Test
    void map_shouldMaskCredentialEmbeddedInValueUnderAnInnocuousKey() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of(
                                "spring.datasource.url",
                                new EnvResponse.PropertyValue(
                                        "jdbc:postgresql://admin:hunter2@localhost/db", "application.properties")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value())
                .isEqualTo("jdbc:postgresql://******@localhost/db");
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

    @Test
    void map_shouldStillMaskWhenUnmaskIsFalse() {
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
}
