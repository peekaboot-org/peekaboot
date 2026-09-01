package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeekabootJsonTest {

    /** JSON has no NaN, so every value headed for the wire maps NaN to null through this one helper. */
    @Test
    void nanBecomesNullAndEverythingElsePassesThrough() {
        assertThat(PeekabootJson.nanToNull(Double.NaN)).isNull();
        assertThat(PeekabootJson.nanToNull(null)).isNull();
        assertThat(PeekabootJson.nanToNull(1.5)).isEqualTo(1.5);
        assertThat(PeekabootJson.nanToNull(0.0)).isEqualTo(0.0);
    }
}
