package net.osslabz.peekaboot.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootAutoConfigurationTest {

    @Test
    void propertiesHaveCorrectDefaults() {
        PeekabootProperties properties = new PeekabootProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getBasePath()).isEqualTo("/peekaboot");
    }

    @Test
    void propertiesCanBeModified() {
        PeekabootProperties properties = new PeekabootProperties();
        properties.setEnabled(false);
        properties.setBasePath("/custom");

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBasePath()).isEqualTo("/custom");
    }
}
