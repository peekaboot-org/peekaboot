package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CronDescriberTest {

    private final CronDescriber describer = new CronDescriber();

    @Test
    void describesEveryHourInEnglish() {
        String result = describer.describe("0 0 * * * *", Locale.ENGLISH);

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase(Locale.ROOT)).contains("hour");
    }

    @Test
    void describesEveryMinuteInGerman() {
        String result = describer.describe("0 * * * * *", Locale.GERMAN);

        // "Minute" reads the same in both languages; the article is what proves the locale reached cron-utils
        assertThat(result).contains("jede").contains("Minute");
    }

    @Test
    void describeSpring53LastDayOfMonthIsSupported() {
        // 'L' (last day of month) is valid @Scheduled syntax since Spring 5.3
        String result = describer.describe("0 0 0 L * *", Locale.ENGLISH);

        assertThat(result).containsIgnoringCase("last day");
    }

    @Test
    void describeNullExpressionReturnsNull() {
        String result = describer.describe(null, Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describeBlankExpressionReturnsNull() {
        String result = describer.describe("   ", Locale.ENGLISH);

        assertThat(result).isNull();
    }

    @Test
    void describeInvalidExpressionReturnsNull() {
        String result = describer.describe("invalid cron expression", Locale.ENGLISH);

        assertThat(result).isNull();
    }
}
