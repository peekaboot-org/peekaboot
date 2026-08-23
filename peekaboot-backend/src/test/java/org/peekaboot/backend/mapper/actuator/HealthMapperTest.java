package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.HealthResponse;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
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

    /**
     * A consuming app's custom HealthIndicator can put anything in its details map -
     * unlike the built-in indicators, its shape isn't controlled by Peekaboot at all.
     */
    @Test
    void map_shouldMaskASensitiveKeyInComponentDetails() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("customIndicator", new HealthResponse.HealthComponent("UP",
                    Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678", "region", "eu-west-1"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("apiKey", "******");
        assertThat(details).containsEntry("region", "eu-west-1");
    }

    @Test
    void map_shouldApplyValuePatternRulesToComponentDetailValues() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("customIndicator", new HealthResponse.HealthComponent("UP",
                    Map.of("endpoint", "https://admin:hunter2@internal.example.com/status"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        assertThat(result.components().get(0).details().get("endpoint"))
            .isEqualTo("https://******@internal.example.com/status");
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("customIndicator", new HealthResponse.HealthComponent("UP",
                    Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health, true);

        assertThat(result.components().get(0).details()).containsEntry("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678");
    }

    @Test
    void map_shouldStillMaskWhenUnmaskIsFalse() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("customIndicator", new HealthResponse.HealthComponent("UP",
                    Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678"))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health, false);

        assertThat(result.components().get(0).details()).containsEntry("apiKey", "******");
    }

    @Test
    void map_shouldLeaveNonStringDetailValuesUntouched() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("diskSpace", new HealthResponse.HealthComponent("UP",
                    Map.of("total", 500_000_000L, "free", 250_000_000L))),
                List.of()
            ),
            200
        );

        HealthInfo result = mapper.map(health);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("total", 500_000_000L);
        assertThat(details).containsEntry("free", 250_000_000L);
    }
}
