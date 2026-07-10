package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootAutoConfigurationTest {

    @Test
    void propertiesHaveCorrectDefaults() {
        PeekabootProperties properties = new PeekabootProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isDevToolbar()).isFalse();
    }

    @Test
    void propertiesCanBeModified() {
        PeekabootProperties properties = new PeekabootProperties();
        properties.setEnabled(false);
        properties.setDevToolbar(true);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDevToolbar()).isTrue();
    }
}
