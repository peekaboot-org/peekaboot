package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.health.HealthInfo;
import net.osslabz.peekaboot.backend.domain.health.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthMapperTest {

    private final HealthMapper mapper = new HealthMapper();

    @Test
    void map_shouldExtractStatusAndComponents() {
        Map<String, Object> actuatorHealth = new LinkedHashMap<>();
        actuatorHealth.put("status", "UP");

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> dbComponent = new LinkedHashMap<>();
        dbComponent.put("status", "UP");
        dbComponent.put("details", Map.of("database", "PostgreSQL"));
        components.put("db", dbComponent);
        actuatorHealth.put("components", components);

        HealthInfo result = mapper.map(actuatorHealth);

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).name()).isEqualTo("db");
    }

    @Test
    void map_shouldHandleNullInput() {
        HealthInfo result = mapper.map(null);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleBodyWrapper() {
        Map<String, Object> wrapped = Map.of("body", Map.of("status", "DOWN", "components", Map.of()));
        HealthInfo result = mapper.map(wrapped);
        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void map_shouldHandleStatusAsMap() {
        Map<String, Object> health = Map.of("status", Map.of("code", "UP"));
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void map_shouldHandleOutOfServiceStatus() {
        Map<String, Object> health = Map.of("status", "OUT_OF_SERVICE");
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }

    @Test
    void map_shouldExtractComponentDetails() {
        Map<String, Object> dbComponent = new LinkedHashMap<>();
        dbComponent.put("status", "UP");
        dbComponent.put("details", Map.of("database", "PostgreSQL", "validationQuery", "isValid()"));

        Map<String, Object> health = Map.of(
            "status", "UP",
            "components", Map.of("db", dbComponent)
        );

        HealthInfo result = mapper.map(health);

        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).details()).containsEntry("database", "PostgreSQL");
    }
}
