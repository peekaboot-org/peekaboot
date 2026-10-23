package org.peekaboot.backend.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

class EnumNamesTest {

    @Test
    void matchesTheNameInAnyCase() {
        assertThat(EnumNames.byName(DayOfWeek.class, "tuesday", DayOfWeek.MONDAY))
                .isEqualTo(DayOfWeek.TUESDAY);
        assertThat(EnumNames.byName(DayOfWeek.class, "Friday", DayOfWeek.MONDAY))
                .isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void fallsBackForANameNoConstantCarries() {
        assertThat(EnumNames.byName(DayOfWeek.class, "someday", DayOfWeek.MONDAY))
                .isEqualTo(DayOfWeek.MONDAY);
        assertThat(EnumNames.byName(DayOfWeek.class, "", DayOfWeek.MONDAY)).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void fallsBackForNull() {
        assertThat(EnumNames.byName(DayOfWeek.class, null, DayOfWeek.MONDAY)).isEqualTo(DayOfWeek.MONDAY);
    }

    /** A caller that drops unknown names passes null as the fallback. */
    @Test
    void acceptsANullFallback() {
        assertThat(EnumNames.byName(DayOfWeek.class, "someday", null)).isNull();
    }
}
