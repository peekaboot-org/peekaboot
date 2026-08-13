package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.HealthResponse;
import net.osslabz.peekaboot.backend.domain.health.HealthInfo;
import net.osslabz.peekaboot.backend.domain.health.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthMapperTest {

    private final HealthMapper mapper = new HealthMapper();

    @Test
    void map_shouldExtractStatusAndComponents() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("db", new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).name()).isEqualTo("db");
        assertThat(result.components().get(0).status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void map_shouldMapPerComponentStatusIndependentlyOfAggregateStatus() {
        // Aggregate status is UP even though the "cache" component itself is DOWN,
        // proving per-component status is read from the component, not copied
        // from the top-level aggregate.
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("cache", new HealthResponse.HealthComponent("DOWN", Map.of())),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.components().get(0).status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void map_shouldHandleNullInput() {
        HealthInfo result = mapper.map(null);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleNullBody() {
        HealthResponse health = new HealthResponse(null, 200);
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleDownStatus() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody("DOWN", Map.of(), List.of()),
            503
        );
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void map_shouldHandleOutOfServiceStatus() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody("OUT_OF_SERVICE", Map.of(), List.of()),
            503
        );
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }

    @Test
    void map_shouldExtractComponentDetails() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("db", new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL", "validationQuery", "isValid()"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).details()).containsEntry("database", "PostgreSQL");
    }
}
