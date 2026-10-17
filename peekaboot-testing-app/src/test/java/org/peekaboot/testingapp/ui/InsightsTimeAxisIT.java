package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Insights charts' time axis. uPlot paints its labels onto the canvas, so the assertions
 * read what it painted off the chart's own axis state: {@code _splits} and {@code _values} are
 * the ticks and label strings its axis pass draws, each line of a label one row below the tick,
 * centred on it. Every chart is built through createChart, the call a panel makes, on a
 * snapshot whose first sample is placed just before a boundary: that is where the axis start
 * and the next rollover each want a second-row label on neighbouring ticks.
 *
 * <p>The page is the dashboard, so the axis font is the real one and Geist is loaded before
 * anything is measured. The browser runs on UTC, so a chart that placed its ticks in the
 * browser's zone rather than the dashboard's is caught the same on every host.
 */
class InsightsTimeAxisIT extends PlaywrightTestBase {

    private static final List<String> SELECTABLE_LOCALES = List.of("en-US", "de-DE", "fr-FR", "es-ES");
    private static final List<Integer> CHART_WIDTHS = List.of(300, 480, 900, 1400);
    private static final Instant NEW_YEAR = Instant.parse("2027-01-01T00:00:00Z");

    /**
     * Builds one chart per scenario and returns, per scenario, the labels its time axis drew
     * and every pair of labels in one row whose boxes overlap. createChart has no handle on
     * its uPlot instance, so the constructor is wrapped for the duration of the call.
     */
    private static final String DRAW_TIME_AXES = """
            async ([chartModule, scenarios]) => {
                const m = await import(chartModule);
                await m.ensureUplot();
                const RealUplot = window.uPlot;
                const results = [];
                try {
                    for (const scenario of scenarios) {
                        let plot;
                        window.uPlot = new Proxy(RealUplot, {
                            construct(target, args) {
                                plot = Reflect.construct(target, args);
                                return plot;
                            }
                        });
                        const mount = document.createElement('div');
                        mount.style.width = scenario.width + 'px';
                        document.body.append(mount);
                        const {count, intervalMs, firstEpochMs} = scenario;
                        const chart = m.createChart({
                            panel: {unit: 'count', chart: 'line', series: [{id: 'load', label: 'Load'}]},
                            mount,
                            level: 0,
                            snapshot: {
                                count, intervalMs, endEpochMs: firstEpochMs + (count - 1) * intervalMs,
                                series: {load: Array.from({length: count}, (_, i) => i % 7)}
                            },
                            showPercentiles: false, events: [], showMarkers: false,
                            dateOptions: () => ({locale: scenario.locale, timeZone: scenario.timeZone}),
                            onZoom() {}, onZoomReset() {}
                        });
                        // uPlot lays out and paints in a microtask after construction
                        await new Promise(resolve => setTimeout(resolve));
                        const axis = plot.axes[0];
                        const measure = document.createElement('canvas').getContext('2d');
                        measure.font = axis.font[0];
                        const rows = [];
                        axis._values.forEach((label, tick) => {
                            if (label == null) return;
                            const x = plot.valToPos(axis._splits[tick], 'x', true);
                            String(label).split('\\n').forEach((text, row) => {
                                const half = measure.measureText(text).width / 2;
                                (rows[row] ??= []).push({text, left: x - half, right: x + half});
                            });
                        });
                        const overlaps = [];
                        rows.forEach(row => row.forEach((label, i) => {
                            const previous = row[i - 1];
                            if (previous && label.left < previous.right) {
                                overlaps.push(`${scenario.name}: '${previous.text}' runs into '${label.text}'`);
                            }
                        }));
                        results.push({labels: axis._values.map(String), overlaps});
                        chart.destroy();
                        mount.remove();
                    }
                } finally {
                    window.uPlot = RealUplot;
                }
                return results;
            }
            """;

    @Override
    protected Page browserContextPage() {
        return browser().newContext(newContextOptions().setTimezoneId("UTC")).newPage();
    }

    @BeforeEach
    void captureBrowserConsole() {
        captureBrowserSignals();
    }

    private static Map<String, Object> scenario(
            String name, Duration interval, int count, Instant firstSample, String locale, String timeZone, int width) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("name", name + " " + locale + " " + timeZone + " " + width + "px");
        // Playwright cannot pass a long into the page; epoch milliseconds are exact as a double
        scenario.put("intervalMs", (double) interval.toMillis());
        scenario.put("count", count);
        scenario.put("firstEpochMs", (double) firstSample.toEpochMilli());
        scenario.put("locale", locale);
        scenario.put("timeZone", timeZone);
        scenario.put("width", width);
        return scenario;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> drawTimeAxes(List<Map<String, Object>> scenarios) {
        openDashboard();
        return (List<Map<String, Object>>) page.evaluate(
                DRAW_TIME_AXES, List.of(contextPath() + "/peekaboot/ui/dashboard/tabs/insights-chart.js", scenarios));
    }

    @SuppressWarnings("unchecked")
    private static List<String> labelsOf(Map<String, Object> drawn) {
        return (List<String>) drawn.get("labels");
    }

    @SuppressWarnings("unchecked")
    private static List<String> overlapsOf(Map<String, Object> drawn) {
        return (List<String>) drawn.get("overlaps");
    }

    /**
     * The capture the defect was found in: ten-second samples starting at 3:44:41 pm, ticks
     * every 15 seconds. The axis start (:45) and the next tick (:00, a new minute) both want a
     * second row; the date belongs on the first of them that the axis can afford, the new
     * minute, so the start keeps a single row.
     */
    @Test
    void theAxisStartGivesUpItsSecondRowToARolloverOnTheNextTick() {
        Map<String, Object> drawn = drawTimeAxes(List.of(scenario(
                        "10s ring filling",
                        Duration.ofSeconds(10),
                        12,
                        Instant.parse("2026-10-17T15:44:41Z"),
                        "en-US",
                        "UTC",
                        480)))
                .getFirst();

        assertThat(overlapsOf(drawn)).isEmpty();
        assertThat(labelsOf(drawn)).startsWith(":45", ":00\n10/17/26, 3:45 PM", ":15");
    }

    /**
     * Every level's interval, its ring filling and full, with the first sample at a few
     * distances before New Year (a minute, hour, day, month and year rollover at once), at
     * every locale the dashboard's select offers and at narrow to wide chart widths.
     */
    @Test
    void noTwoTimeLabelsOverlapAtAnyLevelLocaleOrWidth() {
        Map<String, List<Object>> levels = new LinkedHashMap<>();
        levels.put("10s ring filling", List.of(Duration.ofSeconds(10), 12));
        levels.put("10s ring full", List.of(Duration.ofSeconds(10), 90));
        levels.put("1m ring filling", List.of(Duration.ofMinutes(1), 60));
        levels.put("1m ring full", List.of(Duration.ofMinutes(1), 1440));
        levels.put("1h ring filling", List.of(Duration.ofHours(1), 48));
        levels.put("1h ring full", List.of(Duration.ofHours(1), 720));
        List<Duration> leadsBeforeNewYear = List.of(
                Duration.ofSeconds(19),
                Duration.ofMinutes(4).plusSeconds(19),
                Duration.ofHours(2),
                Duration.ofHours(26));

        List<Map<String, Object>> scenarios = new ArrayList<>();
        levels.forEach((name, level) -> leadsBeforeNewYear.forEach(lead ->
                SELECTABLE_LOCALES.forEach(locale -> CHART_WIDTHS.forEach(width -> scenarios.add(scenario(
                        name + " from " + lead,
                        (Duration) level.get(0),
                        (Integer) level.get(1),
                        NEW_YEAR.minus(lead),
                        locale,
                        "UTC",
                        width))))));

        List<String> overlaps = drawTimeAxes(scenarios).stream()
                .flatMap(drawn -> overlapsOf(drawn).stream())
                .toList();

        assertThat(overlaps).isEmpty();
    }

    /** The same hour, read by an en-US reader in UTC and a de-DE reader on Tokyo time. */
    @Test
    void timeLabelsFollowTheDashboardsLocaleAndTimezone() {
        Instant sixAm = Instant.parse("2026-10-17T06:00:00Z");
        List<Map<String, Object>> drawn = drawTimeAxes(List.of(
                scenario("1m ring", Duration.ofMinutes(1), 61, sixAm, "en-US", "UTC", 900),
                scenario("1m ring", Duration.ofMinutes(1), 61, sixAm, "de-DE", "Asia/Tokyo", 900)));

        assertThat(labelsOf(drawn.get(0))).startsWith("6:00 AM\n10/17/26", "6:05 AM");
        assertThat(labelsOf(drawn.get(1))).startsWith("15:00\n17.10.26", "15:05");
    }

    /** Kolkata is UTC+5:30: ticks placed on the browser's hours would read half past in the dashboard's zone. */
    @Test
    void ticksLandOnTheHoursOfTheDashboardsTimezone() {
        List<String> labels = labelsOf(drawTimeAxes(List.of(scenario(
                        "1m ring full",
                        Duration.ofMinutes(1),
                        1440,
                        Instant.parse("2026-10-17T00:00:00Z"),
                        "de-DE",
                        "Asia/Kolkata",
                        900)))
                .getFirst());

        assertThat(labels)
                .isNotEmpty()
                .allSatisfy(label -> assertThat(label.split("\n", -1)[0]).endsWith(":00"));
    }

    /** Java names some zones in ways Intl rejects ("Z", "GMT+01:00"); the server's zone must not take the chart down. */
    @Test
    void aZoneIntlRejectsStillDrawsTheAxis() {
        Map<String, Object> drawn = drawTimeAxes(List.of(scenario(
                        "1m ring",
                        Duration.ofMinutes(1),
                        60,
                        Instant.parse("2026-10-17T06:00:00Z"),
                        "en-US",
                        "Z",
                        900)))
                .getFirst();

        assertThat(labelsOf(drawn)).isNotEmpty();
        assertThat(overlapsOf(drawn)).isEmpty();
    }
}
