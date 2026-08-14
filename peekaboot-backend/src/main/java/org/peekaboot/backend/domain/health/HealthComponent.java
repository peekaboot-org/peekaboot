package org.peekaboot.backend.domain.health;

import java.util.Map;

public record HealthComponent(
    String name,
    HealthStatus status,
    Map<String, Object> details
) {}
