package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ByteFormatTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0 B",
        "1023, 1023 B",
        "1024, 1.0 KB",
        "1536, 1.5 KB",
        "6081740, 5.8 MB",
        "3221225472, 3.0 GB",
    })
    void picksTheLargestUnitUnderOneThousandTwentyFour(long bytes, String expected) {
        assertThat(ByteFormat.humanize(bytes)).isEqualTo(expected);
    }

    /**
     * {@code Locale.setDefault} is JVM-global. Safe here because the backend's surefire runs
     * its classes one at a time on one thread (the pom sets no parallelism), and the default
     * is restored in {@code finally}; see TESTING.md.
     */
    @Test
    void writesTheDecimalPointWhateverTheDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertThat(ByteFormat.humanize(1536)).isEqualTo("1.5 KB");
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
