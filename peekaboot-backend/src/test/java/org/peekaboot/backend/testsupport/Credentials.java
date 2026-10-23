package org.peekaboot.backend.testsupport;

import java.time.Instant;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.backend.security.PasswordHash;

/** {@link DashboardCredentials} for {@code orders-admin}, created at one fixed instant, in each origin. */
public final class Credentials {

    public static final Instant CREATED = Instant.parse("2026-09-11T08:15:30Z");

    private static final String USERNAME = "orders-admin";

    private Credentials() {}

    /** Generated and persisted on this run, so the plaintext is still known. */
    public static DashboardCredentials generated(String password) {
        return of(password, password, DashboardCredentials.Origin.GENERATED);
    }

    /** Generated on this run and not persisted. */
    public static DashboardCredentials unpersisted(String password) {
        return of(password, password, DashboardCredentials.Origin.GENERATED_UNPERSISTED);
    }

    /** Read back from the credentials file, so only the hash is known. */
    public static DashboardCredentials loaded(String password) {
        return of(password, null, DashboardCredentials.Origin.LOADED);
    }

    /** Taken from {@code peekaboot.security.password}. */
    public static DashboardCredentials configured(String password) {
        return of(password, null, DashboardCredentials.Origin.CONFIGURED);
    }

    private static DashboardCredentials of(String password, String plaintext, DashboardCredentials.Origin origin) {
        return new DashboardCredentials(USERNAME, PasswordHash.of(password), plaintext, CREATED, origin);
    }
}
