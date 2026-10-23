package org.peekaboot.backend.domain.health;

import org.peekaboot.backend.text.EnumNames;

public enum HealthStatus {
    UP,
    DOWN,
    OUT_OF_SERVICE,
    UNKNOWN;

    public static HealthStatus fromString(String status) {
        return EnumNames.byName(HealthStatus.class, status, UNKNOWN);
    }
}
