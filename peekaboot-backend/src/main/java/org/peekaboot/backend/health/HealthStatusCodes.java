package org.peekaboot.backend.health;

/** The HTTP status the application's health endpoint answers a health status with. */
@FunctionalInterface
public interface HealthStatusCodes {

    int of(String status);
}
