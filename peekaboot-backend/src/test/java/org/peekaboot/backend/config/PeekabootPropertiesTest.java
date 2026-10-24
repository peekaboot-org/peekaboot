package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeekabootPropertiesTest {

    /** Folding decides only what a trace shows first; one control reveals the rest. */
    @Test
    void foldsStackTracesByDefault() {
        assertThat(new PeekabootProperties().getStackTrace().isFold()).isTrue();
    }
}
