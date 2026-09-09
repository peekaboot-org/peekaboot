package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IntervalFormatTest {

    @Test
    void wholeHoursMinutesAndSecondsUseTheirOwnUnit() {
        assertThat(IntervalFormat.humanize(Duration.ofHours(2))).isEqualTo("2h");
        assertThat(IntervalFormat.humanize(Duration.ofMinutes(1))).isEqualTo("1m");
        assertThat(IntervalFormat.humanize(Duration.ofSeconds(10))).isEqualTo("10s");
    }

    @Test
    void anythingElseIsMillis() {
        assertThat(IntervalFormat.humanize(Duration.ofMillis(1_500))).isEqualTo("1500ms");
        assertThat(IntervalFormat.humanize(Duration.ofMillis(250))).isEqualTo("250ms");
    }

    /** The unit is the largest one the interval is a whole multiple of, never a rounded larger one. */
    @Test
    void ninetySecondsIsNotAMinuteAndAHalf() {
        assertThat(IntervalFormat.humanize(Duration.ofSeconds(90))).isEqualTo("90s");
    }
}
