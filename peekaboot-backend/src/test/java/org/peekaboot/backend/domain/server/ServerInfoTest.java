package org.peekaboot.backend.domain.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ServerInfoTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Clock SUMMER = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), BERLIN);

    @Test
    void theTimezoneFieldsDescribeTheClocksZoneAtItsInstant() {
        ServerInfo info = ServerInfo.current(Locale.ENGLISH, SUMMER);

        assertThat(info.timezone()).isEqualTo("Europe/Berlin");
        assertThat(info.timezoneOffset()).isEqualTo("+02:00");
        assertThat(info.timezoneDisplay()).isEqualTo("Central European Time");
        assertThat(info.currentTime()).isEqualTo("2026-07-01T14:00:00+02:00");
    }

    @Test
    void theOffsetFollowsTheInstantNotTheZonesStandardOffset() {
        Clock winter = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), BERLIN);

        assertThat(ServerInfo.current(Locale.ENGLISH, winter).timezoneOffset()).isEqualTo("+01:00");
    }

    @Test
    void theDisplayNamesFollowTheRequestLocale() {
        assertThat(ServerInfo.current(Locale.GERMAN, SUMMER).timezoneDisplay()).isEqualTo("Mitteleuropäische Zeit");
    }

    @Test
    void aMissingRequestLocaleFallsBackToEnglish() {
        assertThat(ServerInfo.current(null, SUMMER).timezoneDisplay()).isEqualTo("Central European Time");
    }

    /** The separator is shown as text on the Overview, so the control characters become their escapes. */
    @Test
    void theLineSeparatorIsEscapedForDisplay() {
        assertThat(ServerInfo.current(Locale.ENGLISH, SUMMER).lineSeparator())
                .doesNotContain("\n", "\r")
                .isIn("\\n", "\\r\\n");
    }
}
