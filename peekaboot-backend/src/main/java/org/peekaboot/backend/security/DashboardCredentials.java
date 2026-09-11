package org.peekaboot.backend.security;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The credentials the dashboard guard checks against, and where they came from.
 *
 * @param plaintext the generated password, present only on the run that generated it and
 *     never for a configured or loaded one
 */
public record DashboardCredentials(
        String username,
        PasswordHash passwordHash,
        @Nullable String plaintext,
        Instant createdAt,
        Origin origin) {

    public enum Origin {
        /** Taken from {@code peekaboot.security.password}; nothing was written to disk. */
        CONFIGURED,
        /** Read back from the credentials file, so the password is unchanged since it was generated. */
        LOADED,
        /** Generated on this run and persisted. */
        GENERATED,
        /** Generated on this run and not persisted, so the next restart will generate another. */
        GENERATED_UNPERSISTED
    }

    /** The generated toString would print the password into every log that reports the posture. */
    @Override
    public String toString() {
        return "DashboardCredentials[username=" + username + ", origin=" + origin + ", createdAt=" + createdAt
                + ", password=<redacted>]";
    }
}
