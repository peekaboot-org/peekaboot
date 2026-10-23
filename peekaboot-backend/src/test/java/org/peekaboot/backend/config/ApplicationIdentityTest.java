package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class ApplicationIdentityTest {

    private static BuildProperties build(String group, String artifact) {
        Properties entries = new Properties();
        if (group != null) {
            entries.setProperty("group", group);
        }
        if (artifact != null) {
            entries.setProperty("artifact", artifact);
        }
        return new BuildProperties(entries);
    }

    @Test
    void theBuildCoordinatesNameTheApplication() {
        ApplicationIdentity identity = new ApplicationIdentity(build("com.acme", "orders-service"), "orders");

        assertThat(identity.qualifiedId()).isEqualTo("com.acme.orders-service");
        assertThat(identity.shortName()).isEqualTo("orders-service");
    }

    @Test
    void theApplicationNameStandsInWithoutBuildInformation() {
        ApplicationIdentity identity = new ApplicationIdentity(null, "orders");

        assertThat(identity.qualifiedId()).isEqualTo("orders");
        assertThat(identity.shortName()).isEqualTo("orders");
    }

    /** The qualified id needs both coordinates; the artifact alone still names the short form. */
    @Test
    void incompleteCoordinatesFallBackPerName() {
        ApplicationIdentity withoutGroup = new ApplicationIdentity(build(null, "orders-service"), "orders");

        assertThat(withoutGroup.qualifiedId()).isEqualTo("orders");
        assertThat(withoutGroup.shortName()).isEqualTo("orders-service");
    }

    @Test
    void nothingToGoOnIsNull() {
        ApplicationIdentity identity = new ApplicationIdentity(null, null);

        assertThat(identity.qualifiedId()).isNull();
        assertThat(identity.shortName()).isNull();
    }
}
