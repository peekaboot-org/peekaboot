package net.osslabz.peekaboot.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CronDescriptionServiceTest {

    private final CronDescriptionService service = new CronDescriptionService();

    @Test
    void describe_everyHour_englishLocale() {
        String result = service.describe("0 0 * * * *", Locale.ENGLISH);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase()).contains("hour");
    }

    @Test
    void describe_everyMinute_germanLocale() {
        String result = service.describe("0 * * * * *", Locale.GERMAN);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase()).contains("minute");
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
        assertThat(result.toLowerCase()).contains("hour");
    }
}
