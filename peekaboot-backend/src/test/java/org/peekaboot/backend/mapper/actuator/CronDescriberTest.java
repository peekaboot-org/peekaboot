package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CronDescriberTest {

    private final CronDescriber describer = new CronDescriber();

    @Test
    void describe_everyHour_englishLocale() {
        String result = describer.describe("0 0 * * * *", Locale.ENGLISH);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("hour");
    }

    @Test
    void describe_everyMinute_germanLocale() {
        String result = describer.describe("0 * * * * *", Locale.GERMAN);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("minute");
    }

    @Test
    void describe_spring53LastDayOfMonth_isSupported() {
        // 'L' (last day of month) is valid @Scheduled syntax since Spring 5.3
        String result = describer.describe("0 0 0 L * *", Locale.ENGLISH);

        assertThat(result).containsIgnoringCase("last day");
    }

    @Test
    void describe_nullExpression_returnsNull() {
        String result = describer.describe(null, Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_blankExpression_returnsNull() {
        String result = describer.describe("   ", Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_invalidExpression_returnsNull() {
        String result = describer.describe("invalid cron expression", Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_nullLocale_defaultsToEnglish() {
        String result = describer.describe("0 0 * * * *", null);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("hour");
    }
}
