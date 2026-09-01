package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CronDescriptionServiceTest {

    private final CronDescriptionService service = new CronDescriptionService();

    @Test
    void describe_everyHour_englishLocale() {
        String result = service.describe("0 0 * * * *", Locale.ENGLISH);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("hour");
    }

    @Test
    void describe_everyMinute_germanLocale() {
        String result = service.describe("0 * * * * *", Locale.GERMAN);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("minute");
    }

    @Test
    void describe_spring53LastDayOfMonth_isSupported() {
        // 'L' (last day of month) is valid @Scheduled syntax since Spring 5.3
        String result = service.describe("0 0 0 L * *", Locale.ENGLISH);

        assertThat(result).isNotNull();
    }

    @Test
    void describe_nullExpression_returnsNull() {
        String result = service.describe(null, Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_blankExpression_returnsNull() {
        String result = service.describe("   ", Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_invalidExpression_returnsNull() {
        String result = service.describe("invalid cron expression", Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describe_nullLocale_defaultsToEnglish() {
        String result = service.describe("0 0 * * * *", null);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("hour");
    }
}
