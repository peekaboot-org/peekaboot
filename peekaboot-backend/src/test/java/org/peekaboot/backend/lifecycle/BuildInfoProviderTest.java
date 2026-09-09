package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class BuildInfoProviderTest {

    private static BuildInfoProvider populated() {
        Properties properties = new Properties();
        properties.setProperty("name", "orders");
        properties.setProperty("group", "com.acme");
        properties.setProperty("artifact", "orders-service");
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "1756000000000");
        return new BuildInfoProvider(new BuildProperties(properties));
    }

    @Test
    void reportsTheBuildsCoordinates() {
        BuildInfoProvider provider = populated();

        assertThat(provider.isBuildInfoAvailable()).isTrue();
        assertThat(provider.getName()).isEqualTo("orders");
        assertThat(provider.getFormattedInfo()).isEqualTo("com.acme:orders-service:1.2.3");
        assertThat(provider.getEntries())
                .containsExactly(Map.entry("time", "1756000000000"), Map.entry("version", "1.2.3"));
    }

    /** Without build-info on the classpath every fact reads as unknown rather than throwing. */
    @Test
    void reportsUnknownWithoutBuildProperties() {
        BuildInfoProvider provider = new BuildInfoProvider(null);

        assertThat(provider.isBuildInfoAvailable()).isFalse();
        assertThat(provider.getName()).isEqualTo("unknown");
        assertThat(provider.getGroup()).isEqualTo("unknown");
        assertThat(provider.getArtifact()).isEqualTo("unknown");
        assertThat(provider.getVersion()).isEqualTo("unknown");
        assertThat(provider.getFormattedInfo()).isEqualTo("Build information not available");
        assertThat(provider.getEntries()).isEmpty();
    }
}
