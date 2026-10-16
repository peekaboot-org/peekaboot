package org.peekaboot.backend.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountFormatTest {

    @Test
    void oneTakesTheSingular() {
        assertThat(CountFormat.of(1, "line")).isEqualTo("1 line");
    }

    @Test
    void anyOtherCountTakesThePlural() {
        assertThat(CountFormat.of(0, "line")).isEqualTo("0 lines");
        assertThat(CountFormat.of(2, "line")).isEqualTo("2 lines");
    }

    @Test
    void aMultiWordUnitPluralizesItsLastWord() {
        assertThat(CountFormat.of(3, "parameter set")).isEqualTo("3 parameter sets");
    }
}
