package org.peekaboot.backend.domain.health;

import java.util.List;

/**
 * Rich domain record for health information.
 *
 * Uses explicit typing because health data has complex nested structure
 * with status enums and component aggregation logic.
 */
public record HealthInfo(HealthStatus status, List<HealthComponent> components) {}
