package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = new EnvironmentMapper();

    @Test
    void map_shouldExtractActiveProfiles() {
        Map<String, Object> env = Map.of("activeProfiles", List.of("dev", "local"));
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).containsExactly("dev", "local");
    }

    @Test
    void map_shouldExtractPropertySources() {
        Map<String, Object> env = Map.of(
            "propertySources", List.of(
                Map.of(
                    "name", "application.properties",
                    "properties", Map.of(
                        "server.port", Map.of("value", "8080", "origin", "application.properties")
                    )
                )
            )
        );
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("application.properties");
        assertThat(result.propertySources().get(0).properties()).hasSize(1);
        assertThat(result.propertySources().get(0).properties().get(0).key()).isEqualTo("server.port");
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("8080");
    }

    @Test
    void map_shouldHandleNullInput() {
        EnvironmentInfo result = mapper.map(null);
        assertThat(result.activeProfiles()).isEmpty();
        assertThat(result.propertySources()).isEmpty();
    }

    @Test
    void map_shouldHandleEmptyProfiles() {
        Map<String, Object> env = Map.of("activeProfiles", List.of());
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).isEmpty();
    }

    @Test
    void map_shouldFilterNullProfiles() {
        List<String> profilesWithNull = new java.util.ArrayList<>();
        profilesWithNull.add("dev");
        profilesWithNull.add(null);
        profilesWithNull.add("prod");
        Map<String, Object> env = Map.of("activeProfiles", profilesWithNull);
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).containsExactly("dev", "prod");
    }
}
