package org.peekaboot.backend.domain.health;

import java.util.List;

/** The aggregated health status and its components. */
public record HealthInfo(HealthStatus status, List<HealthComponent> components) {}
