package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.masking.MaskingEngine;

class HealthMapperTest {

    private final HealthMapper mapper = new HealthMapper(new MaskingEngine());

    @Test
    void map_shouldExtractStatusAndComponents() {
        HealthResponse health = new HealthResponse(
                "UP", Map.of("db", new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL"))));

        HealthInfo result = mapper.map(health, false);

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
        HealthResponse health =
                new HealthResponse("UP", Map.of("cache", new HealthResponse.HealthComponent("DOWN", Map.of())));

        HealthInfo result = mapper.map(health, false);

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
        // insertion-ordered like Spring's TreeMap-backed composite, so the flat order is checkable
        Map<String, HealthResponse.HealthComponent> children = new LinkedHashMap<>();
        children.put("primary", new HealthResponse.HealthComponent("UP", Map.of("database", "PostgreSQL")));
        children.put("reporting", new HealthResponse.HealthComponent("DOWN", Map.of("error", "refused")));
        HealthResponse health =
                new HealthResponse("DOWN", Map.of("db", new HealthResponse.HealthComponent("DOWN", null, children)));

        HealthInfo result = mapper.map(health, false);

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
        HealthInfo result = mapper.map(null, false);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleAnEmptyDescriptor() {
        HealthResponse health = new HealthResponse(null, null);
        HealthInfo result = mapper.map(health, false);
        assertThat(result.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(result.components()).isEmpty();
    }

    @Test
    void map_shouldHandleDownStatus() {
        HealthResponse health = new HealthResponse("DOWN", Map.of());
        HealthInfo result = mapper.map(health, false);
        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void map_shouldHandleOutOfServiceStatus() {
        HealthResponse health = new HealthResponse("OUT_OF_SERVICE", Map.of());
        HealthInfo result = mapper.map(health, false);
        assertThat(result.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }

    @Test
    void map_shouldExtractComponentDetails() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "db",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("database", "PostgreSQL", "validationQuery", "isValid()"))));

        HealthInfo result = mapper.map(health, false);

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
                                Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678", "region", "eu-west-1"))));

        HealthInfo result = mapper.map(health, false);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("apiKey", "******");
        assertThat(details).containsEntry("region", "eu-west-1");
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "customIndicator",
                        new HealthResponse.HealthComponent(
                                "UP", Map.of("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678"))));

        HealthInfo result = mapper.map(health, true);

        assertThat(result.components().get(0).details())
                .containsEntry("apiKey", "sk-abcdefghijklmnopqrstuvwxyz012345678");
    }

    @Test
    void map_shouldLeaveNonStringDetailValuesUntouched() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "diskSpace",
                        new HealthResponse.HealthComponent("UP", Map.of("total", 500_000_000L, "free", 250_000_000L))));

        HealthInfo result = mapper.map(health, false);

        Map<String, Object> details = result.components().get(0).details();
        assertThat(details).containsEntry("total", 500_000_000L);
        assertThat(details).containsEntry("free", 250_000_000L);
    }
}
