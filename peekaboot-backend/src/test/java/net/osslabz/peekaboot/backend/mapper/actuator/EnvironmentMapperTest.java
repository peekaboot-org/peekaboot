package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.EnvResponse;
import net.osslabz.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = new EnvironmentMapper();

    @Test
    void map_shouldExtractActiveProfiles() {
        EnvResponse env = new EnvResponse(List.of("dev", "local"), List.of());
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).containsExactly("dev", "local");
    }

    @Test
    void map_shouldExtractPropertySources() {
        EnvResponse env = new EnvResponse(
            List.of(),
            List.of(new EnvResponse.PropertySource(
                "application.properties",
                Map.of("server.port", new EnvResponse.PropertyValue("8080", "application.properties"))
            ))
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
        EnvResponse env = new EnvResponse(List.of(), List.of());
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).isEmpty();
    }

    @Test
    void map_shouldHandleNullProfiles() {
        EnvResponse env = new EnvResponse(null, null);
        EnvironmentInfo result = mapper.map(env);
        assertThat(result.activeProfiles()).isEmpty();
        assertThat(result.propertySources()).isEmpty();
    }
}
