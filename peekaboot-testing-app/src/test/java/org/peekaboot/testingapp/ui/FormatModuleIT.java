package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises shared/format.js in a real browser, imported from the running app. */
class FormatModuleIT extends PlaywrightTestBase {

    @Test
    void formatDurationScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatDurationMs(0.4)")).isEqualTo("<1ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(250)")).isEqualTo("250ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(1500)")).isEqualTo("1.50s");
        assertThat(evalModule("format.js", "m.formatDurationMs(90000)")).isEqualTo("1.50m");
        assertThat(evalModule("format.js", "m.formatDurationMs(null)")).isEqualTo("-");
    }

    /** Class and method, never the method alone: the same method name recurs across classes. */
    @Test
    void shortTargetKeepsTheLastTwoSegments() {
        assertThat(evalModule("format.js", "m.shortTarget('org.example.TaskService.processItems')"))
                .isEqualTo("TaskService.processItems");
        assertThat(evalModule("format.js", "m.shortTarget('Task.run')")).isEqualTo("Task.run");
        assertThat(evalModule("format.js", "m.shortTarget('run')")).isEqualTo("run");
    }

    @Test
    void formatDurationRejectsNegativeValues() {
        assertThat(evalModule("format.js", "m.formatDurationMs(-5)")).isEqualTo("-");
    }

    /** A timeline axis split into quarters, in one unit chosen by its length. */
    @Test
    void formatTimelineTicksLabelsEveryQuarterInOneUnit() {
        assertThat(evalModule("format.js", "m.formatTimelineTicks(0.05)"))
                .isEqualTo(List.of("0ms", "0.01ms", "0.03ms", "0.04ms", "0.05ms"));
        assertThat(evalModule("format.js", "m.formatTimelineTicks(0.4)"))
                .isEqualTo(List.of("0ms", "0.1ms", "0.2ms", "0.3ms", "0.4ms"));
        assertThat(evalModule("format.js", "m.formatTimelineTicks(3)"))
                .isEqualTo(List.of("0ms", "0.8ms", "1.5ms", "2.3ms", "3.0ms"));
        assertThat(evalModule("format.js", "m.formatTimelineTicks(37)"))
                .isEqualTo(List.of("0ms", "9ms", "19ms", "28ms", "37ms"));
        assertThat(evalModule("format.js", "m.formatTimelineTicks(1200)"))
                .isEqualTo(List.of("0s", "0.30s", "0.60s", "0.90s", "1.20s"));
        assertThat(evalModule("format.js", "m.formatTimelineTicks(240000)"))
                .isEqualTo(List.of("0m", "1.00m", "2.00m", "3.00m", "4.00m"));
    }

    /** formatDurationMs rounds a 3ms trace's quarters to "<1ms 2ms 2ms"; the axis needs every label distinct. */
    @Test
    void formatTimelineTicksNeverRepeatALabel() {
        assertThat(evalModule(
                        "format.js",
                        "[0.001, 0.004, 0.01, 0.3, 0.9, 1, 2, 3, 4, 7, 999, 1000, 1001, 59999, 60000, 123456]"
                                + ".filter(total => new Set(m.formatTimelineTicks(total)).size !== 5)"))
                .isEqualTo(List.of());
    }

    @Test
    void formatIntervalClimbsTheUnitLadderWithOneDecimal() {
        assertThat(evalModule("format.js", "m.formatInterval(250)")).isEqualTo("250ms");
        assertThat(evalModule("format.js", "m.formatInterval(1500)")).isEqualTo("1.5s");
        assertThat(evalModule("format.js", "m.formatInterval(90000)")).isEqualTo("1.5m");
        assertThat(evalModule("format.js", "m.formatInterval(3600000)")).isEqualTo("1h");
        assertThat(evalModule("format.js", "m.formatInterval(172800000)")).isEqualTo("2d");
    }

    @Test
    void formatBytesScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatBytes(0)")).isEqualTo("0 B");
        assertThat(evalModule("format.js", "m.formatBytes(1536)")).isEqualTo("1.50 KB");
        assertThat(evalModule("format.js", "m.formatBytes(-1)")).isEqualTo("-");
    }

    @Test
    void formatBytesHandlesFractionalValuesBelowOne() {
        // Math.log(0.5)/Math.log(1024) is negative, so the exponent must be clamped
        // at zero instead of indexing BYTE_UNITS with -1.
        assertThat(evalModule("format.js", "m.formatBytes(0.5)")).isEqualTo("0.50 B");
    }

    @Test
    void formatHostsRendersHostnamePortAndFallbacks() {
        // API host objects carry "hostname" (net.osslabz.jdbc.Host), not "host"
        assertThat(evalModule("format.js", "m.formatHosts([{hostname: '127.0.0.1', port: 5432}])"))
                .isEqualTo("127.0.0.1:5432");
        assertThat(evalModule(
                        "format.js", "m.formatHosts([{hostname: 'db1', port: 5432}, {hostname: 'db2', port: 5433}])"))
                .isEqualTo("db1:5432, db2:5433");
        assertThat(evalModule("format.js", "m.formatHosts([{hostname: 'db.local'}])"))
                .isEqualTo("db.local");
        assertThat(evalModule("format.js", "m.formatHosts(null)")).isEqualTo("unknown");
        assertThat(evalModule("format.js", "m.formatHosts([])")).isEqualTo("unknown");
    }

    @Test
    void formatDateTimeHandlesUnparseableValueWithoutSayingInvalidDate() {
        Object result = evalModule("format.js", "m.formatDateTime('not-a-timestamp')");
        assertThat(result).isEqualTo("not-a-timestamp");
        assertThat((String) result).doesNotContain("Invalid Date");
    }

    @Test
    void formatTimeOfDayHandlesUnparseableValueWithoutSayingInvalidDate() {
        Object result = evalModule("format.js", "m.formatTimeOfDay('not-a-timestamp')");
        assertThat(result).isEqualTo("not-a-timestamp");
        assertThat((String) result).doesNotContain("Invalid Date");
    }

    @Test
    void formatDateTimeTreatsEpochZeroAsAValidTimestamp() {
        assertThat(evalModule("format.js", "m.formatDateTime(0, {locale: 'en-US', timeZone: 'UTC'})"))
                .isEqualTo("Jan 1, 1970, 12:00 AM");
    }

    /**
     * The dashboard header's "Updated ..." readout passes hour/minute/second only; the
     * options given are the whole set, so no date-first default prepends the day the
     * one-line readout has no room for.
     */
    @Test
    void formatDateTimeWithRendersExactlyTheOptionsGiven() {
        String time = (String) evalModule(
                "format.js",
                "m.formatDateTimeWith(0, {hour: '2-digit', minute: '2-digit', second: '2-digit'},"
                        + " {locale: 'en-US', timeZone: 'UTC'})");
        assertThat(time).contains("12:00:00");
        assertThat(time).doesNotContain("1970").doesNotContain("Jan");
    }

    /** formatTimeOfDay is formatDateTimeWith at millisecond precision: no date, three fraction digits. */
    @Test
    void formatTimeOfDayRendersTheTimeToTheMillisecond() {
        assertThat(evalModule("format.js", "m.formatTimeOfDay(1500, {locale: 'en-US', timeZone: 'UTC'})"))
                .isEqualTo("00:00:01.500");
    }

    /**
     * Java writes an Instant to the nanosecond; every engine keeps only the millisecond. WebKit's
     * own Date.parse rounds 0.250999999s up at a present-day epoch, where a double runs out of
     * digits, though not at 1970's.
     */
    @Test
    void instantMsDropsTheDigitsPastTheMillisecond() {
        assertThat(evalModule("format.js", """
                        [m.instantMs('2026-10-23T10:00:00.250999999Z') - Date.UTC(2026, 9, 23, 10),
                         m.instantMs('2026-10-23T10:00:00.2Z') - Date.UTC(2026, 9, 23, 10),
                         m.instantMs('2026-10-23T12:00:00.999999+02:00') - Date.UTC(2026, 9, 23, 10),
                         m.instantMs('2026-10-23T10:00:00Z') - Date.UTC(2026, 9, 23, 10),
                         String(m.instantMs(null)), String(m.instantMs('not-a-timestamp'))]
                        """)).isEqualTo(List.of(250, 200, 999, 0, "NaN", "NaN"));
    }

    /** A log row shows the millisecond its nanosecond timestamp falls in, in every engine. */
    @Test
    void formatTimeOfDayTruncatesANanosecondTimestamp() {
        assertThat(evalModule(
                        "format.js",
                        "m.formatTimeOfDay('2026-10-23T10:00:01.500999999Z', {locale: 'en-US', timeZone: 'UTC'})"))
                .isEqualTo("10:00:01.500");
    }

    @Test
    void formatTimeOfDayTreatsEpochZeroAsAValidTimestamp() {
        assertThat(evalModule("format.js", "m.formatTimeOfDay(0, {locale: 'en-US', timeZone: 'UTC'})"))
                .isEqualTo("00:00:00.000");
    }

    /**
     * formatCount()'s plural defaults to singular + 's' (covers "span"/"spans", "log"/"logs")
     * but takes an explicit override for irregular nouns like "query"/"queries" - without it
     * a trace's stat line reads "1 queries".
     */
    @Test
    void formatCountPluralisesIrregularNouns() {
        assertThat(evalModule("format.js", "m.formatCount(1, 'query', {plural: 'queries'})"))
                .isEqualTo("1 query");
        assertThat(evalModule("format.js", "m.formatCount(2, 'query', {plural: 'queries'})"))
                .isEqualTo("2 queries");
        assertThat(evalModule("format.js", "m.formatCount(1, 'span')")).isEqualTo("1 span");
        assertThat(evalModule("format.js", "m.formatCount(0, 'span')")).isEqualTo("0 spans");
    }

    /**
     * A five-digit count is unreadable as a run of digits, so every count is grouped: in the
     * locale a surface passes (the dashboard's setting), else in the browser's.
     */
    @Test
    void countsAreGroupedInTheGivenLocaleElseTheBrowsers() {
        assertThat(evalModule("format.js", "m.formatCount(1234567, 'row')")).isEqualTo("1,234,567 rows");
        assertThat(evalModule("format.js", "m.formatCount(999, 'row')")).isEqualTo("999 rows");
        assertThat(evalModule("format.js", "m.formatCount(1234567, 'row', {locale: 'de-DE'})"))
                .isEqualTo("1.234.567 rows");
        assertThat(evalModule("format.js", "m.formatNumber(12345)")).isEqualTo("12,345");
        assertThat(evalModule("format.js", "m.formatNumber(12345, {locale: 'de-DE'})"))
                .isEqualTo("12.345");
        assertThat(evalModule("format.js", "m.formatMetricValue(1234567, 'count', {locale: 'de-DE'})"))
                .isEqualTo("1.234.567");
        assertThat(evalModule("format.js", "m.formatMetricValue(1234.5, 'count', {locale: 'de-DE'})"))
                .isEqualTo("1.234,50");
        assertThat(evalModule("format.js", "m.formatTileValue(12345, 'count', {locale: 'de-DE'})"))
                .isEqualTo("12.345");
    }

    /**
     * Every unit a series or tile can carry, so a chart's axis and a tile's readout cannot
     * silently render a raw number. percent arrives as 0..100 and only gains its sign,
     * and the two rate units are the only ones with a suffix.
     */
    @Test
    void formatMetricValueRendersEveryUnitItsOwnWay() {
        assertThat(evalModule("format.js", "m.formatMetricValue(1536, 'bytes')"))
                .isEqualTo("1.50 KB");
        assertThat(evalModule("format.js", "m.formatMetricValue(42, 'percent')"))
                .isEqualTo("42.0%");
        assertThat(evalModule("format.js", "m.formatMetricValue(1500, 'millis')"))
                .isEqualTo("1.50s");
        assertThat(evalModule("format.js", "m.formatMetricValue(12.345, 'persec')"))
                .isEqualTo("12/s");
        assertThat(evalModule("format.js", "m.formatMetricValue(2048, 'bytes-persec')"))
                .isEqualTo("2.00 KB/s");
        assertThat(evalModule("format.js", "m.formatMetricValue(7, 'count')")).isEqualTo("7");
        assertThat(evalModule("format.js", "m.formatMetricValue(7, 'no-such-unit')"))
                .as("an unknown unit falls back to the count format rather than to nothing")
                .isEqualTo("7");
        assertThat(evalModule("format.js", "m.formatMetricValue(null, 'bytes')"))
                .isEqualTo("-");
        assertThat(evalModule("format.js", "m.formatMetricValue(NaN, 'bytes')")).isEqualTo("-");
    }

    /** The backend serves a time tile in milliseconds, a duration or an epoch instant, which the formatters take as they are. */
    @Test
    void formatTileValueTakesMilliseconds() {
        assertThat(evalModule("format.js", "m.formatTileValue(1500, 'duration')"))
                .isEqualTo("1.50s");
        assertThat(evalModule(
                        "format.js",
                        "m.formatTileValue(1762400000000, 'datetime', {locale: 'en-US', timeZone: 'UTC'})"))
                .isEqualTo(
                        evalModule("format.js", "m.formatDateTime(1762400000000, {locale: 'en-US', timeZone: 'UTC'})"))
                .asString()
                .contains("2025");
    }
}
