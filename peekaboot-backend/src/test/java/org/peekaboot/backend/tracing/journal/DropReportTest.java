package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DropReportTest {

    private static final Instant T0 = Instant.parse("2026-10-31T08:00:00Z");

    private final DropReport report = new DropReport();

    @Test
    void nothingDroppedIsNothingToReport() {
        assertThat(report.due(T0)).isZero();
    }

    /** The first drop is reported at once; the next report waits a minute and then carries everything since. */
    @Test
    void reportsAtMostOnceAMinuteWithTheCountSince() {
        report.count();
        report.count();
        assertThat(report.due(T0)).isEqualTo(2);

        report.count();
        assertThat(report.due(T0.plusSeconds(59))).isZero();
        report.count();
        assertThat(report.due(T0.plusSeconds(60))).isEqualTo(2);
        assertThat(report.due(T0.plusSeconds(200))).isZero();
    }
}
