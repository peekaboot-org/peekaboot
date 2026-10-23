package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.Credentials;

class DashboardCredentialsTest {

    /** The record is logged wherever the posture is; its generated toString would print the password. */
    @Test
    void toString_neverCarriesThePlaintext() {
        var credentials = Credentials.generated("s3cret");

        assertThat(credentials.toString()).doesNotContain("s3cret").contains("orders-admin", "GENERATED");
    }

    @Test
    void withOrigin_changesTheOriginAlone() {
        var generated = Credentials.generated("s3cret");

        var unpersisted = generated.withOrigin(DashboardCredentials.Origin.GENERATED_UNPERSISTED);

        assertThat(unpersisted.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED_UNPERSISTED);
        assertThat(unpersisted)
                .usingRecursiveComparison()
                .ignoringFields("origin")
                .isEqualTo(generated);
    }
}
