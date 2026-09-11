package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeekabootPropertiesTest {

    @Test
    void securityIsOffAndUnconfiguredByDefault() {
        var properties = new PeekabootProperties();

        assertThat(properties.getSecurity().isEnabled()).isFalse();
        assertThat(properties.getSecurity().getUsername()).isNull();
        assertThat(properties.getSecurity().getPassword()).isNull();
        assertThat(properties.getSecurity().getCredentialsFile()).isNull();
    }
}
