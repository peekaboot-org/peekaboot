package org.peekaboot.backend.domain.health;

public enum HealthStatus {
    UP,
    DOWN,
    OUT_OF_SERVICE,
    UNKNOWN;

    public static HealthStatus fromString(String status) {
        if (status == null) return UNKNOWN;
        return switch (status.toUpperCase()) {
            case "UP" -> UP;
            case "DOWN" -> DOWN;
            case "OUT_OF_SERVICE" -> OUT_OF_SERVICE;
            default -> UNKNOWN;
        };
    }
}
