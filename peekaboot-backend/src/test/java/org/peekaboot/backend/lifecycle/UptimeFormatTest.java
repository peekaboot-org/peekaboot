package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UptimeFormatTest {

    @Test
    void secondsAreEnoughBelowAMinute() {
        assertThat(UptimeFormat.humanize(Duration.ofSeconds(45))).isEqualTo("45 seconds");
    }

    @Test
    void aSingleUnitIsNotPluralized() {
        assertThat(UptimeFormat.humanize(Duration.ofSeconds(1))).isEqualTo("1 second");
        assertThat(UptimeFormat.humanize(Duration.ofHours(1))).isEqualTo("1 hour");
    }

    @Test
    void thePreciseCaseReadsAsThreeUnits() {
        Duration uptime = Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4);

        assertThat(UptimeFormat.humanize(uptime)).isEqualTo("1 day, 2 hours, 3 minutes");
    }

    @Test
    void unitsThatAreZeroAreLeftOutRatherThanPadded() {
        assertThat(UptimeFormat.humanize(Duration.ofDays(1).plusMinutes(3))).isEqualTo("1 day, 3 minutes");
    }

    @Test
    void anExactUnitStandsAlone() {
        assertThat(UptimeFormat.humanize(Duration.ofHours(2))).isEqualTo("2 hours");
    }

    @Test
    void aRunTooShortToMeasureStillReadsAsADuration() {
        assertThat(UptimeFormat.humanize(Duration.ZERO)).isEqualTo("0 seconds");
        assertThat(UptimeFormat.humanize(Duration.ofSeconds(-5))).isEqualTo("0 seconds");
    }
}
