package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.config.ConfigInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMapperTest {

    private final ConfigMapper mapper = new ConfigMapper();

    @Test
    void map_shouldGroupByPrefix() {
        Map<String, Object> configprops = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "beans", Map.of(
                        "spring.datasource-org.springframework.boot.autoconfigure.jdbc.DataSourceProperties", Map.of(
                            "prefix", "spring.datasource",
                            "properties", Map.of("url", "jdbc:h2:mem:test", "driverClassName", "org.h2.Driver")
                        ),
                        "server-org.springframework.boot.autoconfigure.web.ServerProperties", Map.of(
                            "prefix", "server",
                            "properties", Map.of("port", "8080")
                        )
                    )
                )
            )
        );

        ConfigInfo result = mapper.map(configprops);

        assertThat(result.groups()).hasSize(2);
    }

    @Test
    void map_shouldMaskSensitiveProperties() {
        Map<String, Object> configprops = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "beans", Map.of(
                        "datasource", Map.of(
                            "prefix", "spring.datasource",
                            "properties", Map.of("password", "secret123", "username", "admin")
                        )
                    )
                )
            )
        );

        ConfigInfo result = mapper.map(configprops);

        assertThat(result.groups().get(0).properties()).anyMatch(p -> p.key().equals("password") && p.value().equals("********"));
        assertThat(result.groups().get(0).properties()).anyMatch(p -> p.key().equals("username") && p.value().equals("admin"));
    }

    @Test
    void map_shouldHandleNullInput() {
        ConfigInfo result = mapper.map(null);
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void map_shouldHandleMissingContexts() {
        ConfigInfo result = mapper.map(Map.of());
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void map_shouldMaskCredentialsKey() {
        Map<String, Object> configprops = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "beans", Map.of(
                        "aws", Map.of(
                            "prefix", "aws",
                            "properties", Map.of("credentials", "AKIA...")
                        )
                    )
                )
            )
        );

        ConfigInfo result = mapper.map(configprops);
        assertThat(result.groups().get(0).properties().get(0).value()).isEqualTo("********");
    }
}
