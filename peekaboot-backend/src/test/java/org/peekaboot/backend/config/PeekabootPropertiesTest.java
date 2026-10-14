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

    /** Folding decides only what a trace shows first; one control reveals the rest. */
    @Test
    void foldsStackTracesByDefault() {
        assertThat(new PeekabootProperties().getStackTrace().isFold()).isTrue();
    }
}
