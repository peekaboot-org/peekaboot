package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;

class HealthMapperTest {

    private final HealthMapper mapper = new HealthMapper();

    @Test
    void map_shouldExtractStatusAndComponents() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of("db", new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL"))),
                List.of());

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
                "UP", Map.of("cache", new HealthResponse.HealthComponent("DOWN", Map.of())), List.of());

        HealthInfo result = mapper.map(health);

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.components().get(0).status()).isEqualTo(HealthStatus.DOWN);
    }

    /**
     * A composite contributor - Spring's {@code db} as soon as there are two DataSources,
     * or any custom composite - nests its children under {@code components}. The dashboard
     * shows one flat list, so the children are named after their parent, and the composite
     * itself stays in the list with its aggregate status.
     */
    @Test
    void map_shouldFlattenACompositesChildrenUnderTheParentName() {
        HealthResponse health = new HealthResponse(
                "DOWN",
                Map.of(
                        "db",
                        new HealthResponse.HealthComponent(
                                "DOWN",
                                null,
                                Map.of(
                                        "primary",
                                        new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL")),
                                        "reporting",
                                        new HealthResponse.HealthComponent("DOWN", Map.of("error", "refused"))))),
                List.of());

        HealthInfo result = mapper.map(health);

        assertThat(result.components())
                .extracting(HealthComponent::name, HealthComponent::status)
                .containsExactly(
                        tuple("db", HealthStatus.DOWN),
                        tuple("db/primary", HealthStatus.UP),
                        tuple("db/reporting", HealthStatus.DOWN));
        assertThat(result.components().get(0).details()).isEmpty();
        assertThat(result.components().get(1).details()).containsEntry("database", "PostgreSQL");
    }

    @Test
    void map_shouldHandleNullInput() {
        HealthInfo result = mapper.map(null);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleAnEmptyDescriptor() {
        HealthResponse health = new HealthResponse(null, null, null);
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleDownStatus() {
        HealthResponse health = new HealthResponse("DOWN", Map.of(), List.of());
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void map_shouldHandleOutOfServiceStatus() {
        HealthResponse health = new HealthResponse("OUT_OF_SERVICE", Map.of(), List.of());
        HealthInfo result = mapper.map(health);
        assertThat(result.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }

    @Test
    void map_shouldExtractComponentDetails() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "db",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("database", "PostgreSQL", "validationQuery", "isValid()"))),
                List.of());

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
                "UP",
                Map.of(
                        "customIndicator",
                        new HealthResponse.HealthComponent(
                                "UP",
                                Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678", "region", "eu-west-1"))),
                List.of());

        HealthInfo result = mapper.map(health);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("apiKey", "******");
        assertThat(details).containsEntry("region", "eu-west-1");
    }

    @Test
    void map_shouldApplyValuePatternRulesToComponentDetailValues() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "customIndicator",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("endpoint", "https://admin:hunter2@internal.example.com/status"))),
                List.of());

        HealthInfo result = mapper.map(health);

        assertThat(result.components().get(0).details().get("endpoint"))
                .isEqualTo("https://******@internal.example.com/status");
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "customIndicator",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678"))),
                List.of());

        HealthInfo result = mapper.map(health, true);

        assertThat(result.components().get(0).details())
                .containsEntry("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678");
    }

    @Test
    void map_shouldStillMaskWhenUnmaskIsFalse() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "customIndicator",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678"))),
                List.of());

        HealthInfo result = mapper.map(health, false);

        assertThat(result.components().get(0).details()).containsEntry("apiKey", "******");
    }

    @Test
    void map_shouldLeaveNonStringDetailValuesUntouched() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "diskSpace",
                        new HealthResponse.HealthComponent("UP", Map.of("total", 500_000_000L, "free", 250_000_000L))),
                List.of());

        HealthInfo result = mapper.map(health);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("total", 500_000_000L);
        assertThat(details).containsEntry("free", 250_000_000L);
    }
}
