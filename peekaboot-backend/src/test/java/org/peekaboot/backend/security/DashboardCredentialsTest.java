package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DashboardCredentialsTest {

    /** The record is logged wherever the posture is; its generated toString would print the password. */
    @Test
    void toString_neverCarriesThePlaintext() {
        var credentials = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("s3cret"),
                "s3cret",
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.GENERATED);

        assertThat(credentials.toString()).doesNotContain("s3cret").contains("orders-admin", "GENERATED");
    }
}
