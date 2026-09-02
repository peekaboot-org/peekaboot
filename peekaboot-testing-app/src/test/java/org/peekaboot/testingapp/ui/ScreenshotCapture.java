package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.lifecycle.LifecycleEventFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Captures the screenshots the peekaboot.org website ships. A tool, not a test: it asserts
 * only that it produced files, and it is deliberately <strong>not</strong> named
 * {@code *Test} so surefire's default includes never pick it up.
 *
 * <pre>
 * mvn -pl peekaboot-testing-app test \
 *     -Dtest=ScreenshotCapture \
 *     -Dpeekaboot.screenshots.out=/absolute/output/dir
 * </pre>
 *
 * <p>Runs under the {@code screenshots} profile (real Postgres via Docker Compose, Flyway
 * on) so every dashboard tab has genuine content. Docker must be running.
 *
 * <p>The Lifecycle tab gets a seeded run history the same way {@link LifecycleTabIT} does
 * (storage on, pointed at a temp directory holding a {@link LifecycleHistoryFixture}): a
 * JUnit launch is never a local run, so storage is off under this profile and the tab
 * would otherwise photograph the application's own run as its only row. The context is
 * closed with the class for the reason {@code LifecycleTabIT} gives: the storage
 * directory dies with the class, and a cached context would keep writing into it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// inheritProfiles = false: PlaywrightTestBase carries @ActiveProfiles("test") - merging
// that in (the default) would activate both "test" and "screenshots", and Spring Boot's
// last-profile-wins rule applies to the *resolved* order rather than declaration order,
// so "test"'s H2/Flyway-disabled settings would override this profile's Postgres config.
@ActiveProfiles(profiles = "screenshots", inheritProfiles = false)
class ScreenshotCapture extends PlaywrightTestBase {

    private static final String OUTPUT_PROPERTY = "peekaboot.screenshots.out";
    private static final int VIEWPORT_WIDTH = 1440;
    private static final int VIEWPORT_HEIGHT = 900;

    // Insights is deliberately not in this list: its charts fill from a ring the server
    // samples on a fixed cadence, so it is captured last, once that ring holds enough
    // history to draw a line (see captureInsights) - not in strip order between Overview
    // and Lifecycle, which would photograph a chart with one or two points. The tabs
    // here are ordered to match the tab strip's own order (see dashboard/main.js's TABS
    // array).
    private static final List<String> DASHBOARD_TABS = List.of(
            "overview",
            "lifecycle",
            "traces",
            "meters",
            "environment",
            "flyway",
            "loggers",
            "config",
            "scheduled-tasks");

    private static final String INSIGHTS_TAB = "insights";

    /**
     * The selector each tab waits for beyond "panel active" before it counts as rendered
     * with real data. Without this a tab can be photographed mid-fetch (an empty list),
     * since {@code #<id>-tab.active} only proves the panel is showing, not that its own
     * render() call has finished populating it.
     */
    private static final Map<String, String> TAB_READY_SELECTOR = Map.of(
            "overview", "#memory-info .pk-meter__fill",
            "lifecycle", "#lifecycle-runs .pk-lifecycle-table tbody tr",
            "environment", "#property-sources .pk-group__header",
            "flyway", "#flyway-timeline .pk-table tbody tr",
            "loggers", "#loggers-list .pk-group",
            "config", "#config-groups .pk-group__header",
            "scheduled-tasks", "#scheduled-tasks-groups .pk-group",
            "meters", "#meters-list .pk-group",
            "traces", "#traces-list .pk-trace-item");

    /**
     * The header of one property group per tab that carries a masked value, keyed the
     * same way as {@link #TAB_READY_SELECTOR}. Both tabs render every group collapsed by
     * default, so without this, neither image ever shows a masked value or the reveal
     * control next to one - only collapsed headers.
     *
     * <p>Environment: the {@code application-screenshots.yml} property source, identified
     * by its Spring-assigned name rather than position - property source ordering is not
     * a contract this tool should depend on. Config: the {@code spring.datasource} group,
     * keyed by its {@code @ConfigurationProperties} prefix the same way. Both groups carry
     * the {@code spring.datasource.password} fixture set in that profile (see its own
     * comment) purely so there is a masked value to expand - deliberately not the
     * {@code systemProperties}/{@code systemEnvironment} groups, which on a developer's own
     * machine can carry masked entries whose key names alone (not values) are specific to
     * that machine or shell session and have no business in a published screenshot.
     */
    private static final Map<String, String> MASKED_GROUP_HEADER_SELECTOR = Map.of(
            "environment",
                    "#property-sources .pk-group[data-group-key*=\"application-screenshots.yml\"] .pk-group__header",
            "config", "#config-groups .pk-group[data-group-key=\"spring.datasource\"] .pk-group__header");

    /**
     * The "Show secrets" control for each tab in {@link #MASKED_GROUP_HEADER_SELECTOR},
     * scoped to that tab's own slot ({@code #env-unmask-slot}/{@code #config-unmask-slot})
     * even though the underlying reveal flag is one shared boolean page-wide (see
     * {@code shared/unmask-control.js}'s doc comment) - only the currently active tab's
     * copy of the button is visible for Playwright to click.
     */
    private static final Map<String, String> REVEAL_BUTTON_SELECTOR = Map.of(
            "environment", "#env-unmask-slot .pk-unmask-toggle", "config", "#config-unmask-slot .pk-unmask-toggle");

    /**
     * Where {@link #REVEAL_BUTTON_SELECTOR} and {@link #waitForRevealedRowValue} look for
     * the row that flips from masked to revealed - the container all of a tab's key/value
     * rows render into regardless of which group is expanded (rows exist in the DOM even
     * inside a collapsed group; see {@code UnmaskingControlEnabledIT}'s own doc comment),
     * and the property key each tab renders that row under. Config renders the key relative
     * to its group prefix ({@code password}); Environment renders the full property key
     * ({@code spring.datasource.password}) since a property source has no prefix to strip.
     */
    private static final Map<String, String> REVEALED_ROW_CONTAINER_SELECTOR =
            Map.of("environment", "#property-sources", "config", "#config-groups");

    private static final Map<String, String> REVEALED_ROW_KEY =
            Map.of("environment", "spring.datasource.password", "config", "password");

    /**
     * What {@code spring.datasource.password} reveals to - {@code compose.yml}'s own
     * {@code POSTGRES_PASSWORD}, already plaintext in this repository (see
     * {@code application-screenshots.yml}'s fixture comment). Waiting for this literal
     * value, rather than merely "not {@code ******} any more", pins the revealed shot to
     * exactly the one property this tool is permitted to reveal.
     */
    private static final String REVEALED_FIXTURE_VALUE = "sample_app_db_pwd";

    private static final String MASKED_VALUE = "******";

    /**
     * Seven seeded runs plus the application's own fit on the Lifecycle tab's first page
     * of 20, and between them show every badge the tab has: Running (the application's
     * own run), Unclean exit with a dash duration (run 2) and the dash downtime after
     * it, and Deployment for a version change (run 4) and a branch-and-commit change
     * (run 6). See {@link LifecycleHistoryFixture} for what each index means.
     */
    private static final LifecycleHistoryFixture LIFECYCLE_HISTORY = new LifecycleHistoryFixture(7, 2, 4, 6);

    /**
     * How many level-0 samples the Insights ring must hold before its charts are
     * photographed. The tab renders a chart as soon as any sample exists, but one or two
     * points on a full-width axis read as a broken chart; a dozen at the profile's level-0
     * interval (the defaults' 10 s, so about two minutes of history) draws a real line.
     */
    private static final int MIN_INSIGHTS_SAMPLES = 12;

    /**
     * The traceId of the flagship /orders N+1 request, captured straight from that
     * response's own toolbar payload in {@link #generateTraffic()} - see the field's use
     * in {@link #captureDashboardTabs} for why this is not simply "click the first item
     * in the traces list".
     */
    private String flagshipTraceId;

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void lifecycleStorage(DynamicPropertyRegistry registry) {
        LIFECYCLE_HISTORY.writeTo(storageDir.resolve(LifecycleEventFile.FILE_NAME));
        registry.add("peekaboot.storage.enabled", () -> "true");
        registry.add("peekaboot.storage.dir", () -> storageDir.toString());
    }

    @Override
    protected Page browserContextPage() {
        return browser()
                .newContext(newContextOptions().setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
                .newPage();
    }

    @Test
    void captureEverySurfaceInBothThemes() throws Exception {
        Path outputDir = resolveOutputDir();

        List<String> themes = List.of("light", "dark");
        for (String theme : themes) {
            captureDashboardTabs(outputDir, theme);
            captureToolbar(outputDir, theme);
        }
        // last on purpose: everything above is history for the Insights ring to chart
        // (see MIN_INSIGHTS_SAMPLES), and the traffic it generated is what the HTTP and
        // datasource panels have to show
        for (String theme : themes) {
            captureInsights(outputDir, theme);
        }

        assertThat(outputDir).isDirectoryContaining(path -> path.toString().endsWith(".png"));
    }

    private Path resolveOutputDir() throws Exception {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("-D" + OUTPUT_PROPERTY + "=<absolute-dir> is required; refusing to guess "
                    + "where to write screenshots");
        }
        Path dir = Path.of(configured);
        Files.createDirectories(dir);
        return dir;
    }

    private void captureDashboardTabs(Path outputDir, String theme) {
        newThemedPage(theme);
        generateTraffic();
        openDashboard();
        assertAllTabButtonsVisible();

        for (String tabId : DASHBOARD_TABS) {
            page.click(".pk-tab[data-tab=\"" + tabId + "\"]");
            page.waitForSelector("#" + tabId + "-tab.active");
            page.waitForSelector(TAB_READY_SELECTOR.get(tabId));
            expandMaskedGroupIfPresent(tabId);
            shoot(outputDir, "dashboard-" + tabId + "-" + theme);
            captureRevealedGroupIfPresent(outputDir, tabId, theme);
        }

        // Deep-link to the flagship /orders trace by id, via the hash (which re-navigates
        // regardless of which tab the loop above left active), rather than clicking the
        // list's first (most recent) entry: the Flyway tab's own migration-info lookup
        // opens a JDBC connection outside any request context, which Peekaboot captures
        // as its own root-level "connection" trace, and that trace sorts above /orders by
        // the time this runs.
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", flagshipTraceId);
        page.waitForSelector("#peekaboot-trace-overlay");
        // The host element exists as soon as openTraceDetail() creates it, well before
        // fetchAndRender() replaces the loading placeholder - wait for that placeholder to
        // actually be gone, or the screenshot just shows "Loading trace data...".
        page.waitForFunction(
                "() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay__loading')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        shoot(outputDir, "trace-detail-" + theme);

        // Spans and Queries are independent rendering paths - Spans shows span.name
        // (OpenTelemetry's own summary), Queries shows the SQL QueryExtractor extracts
        // (see docs/IMPROVEMENTS.md §2.1). The overlay opens on Spans, so without this
        // click no shipped image has ever shown QueryExtractor's output. The flagship
        // /orders trace deep-linked above is deliberately the N+1 example, so it is
        // guaranteed to carry real queries to click across to.
        page.click("#peekaboot-trace-overlay .pk-tabs .pk-tab[data-tab=\"queries\"]");
        page.waitForSelector("#peekaboot-trace-overlay .pk-query-item");
        shoot(outputDir, "trace-detail-queries-" + theme);
    }

    /**
     * Fails loudly, naming every missing/hidden tab, rather than letting a later
     * page.click() time out on a selector that quietly never existed. insights, meters
     * and traces are gated on GET /api/features; a silently absent button would otherwise
     * look like an ordinary Playwright timeout with no clue which tab caused it.
     */
    private void assertAllTabButtonsVisible() {
        List<String> missing = new ArrayList<>();
        List<String> expected = new ArrayList<>(DASHBOARD_TABS);
        expected.add(INSIGHTS_TAB);
        for (String tabId : expected) {
            if (!page.isVisible(".pk-tab[data-tab=\"" + tabId + "\"]")) {
                missing.add(tabId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("dashboard tab button(s) missing or hidden, cannot capture: " + missing);
        }
    }

    /**
     * Photographs the Insights tab once its ring holds {@link #MIN_INSIGHTS_SAMPLES}
     * level-0 samples and every panel in the viewport has either drawn its chart or
     * declared itself empty. A chart exists as soon as uPlot has built it, so the wait is
     * for a canvas with a real width, not merely for the element; below-the-fold panels
     * are created lazily on scroll and are not part of the shot.
     */
    private void captureInsights(Path outputDir, String theme) {
        newThemedPage(theme);
        openDashboard();
        waitForInsightsHistory();

        page.click(".pk-tab[data-tab=\"" + INSIGHTS_TAB + "\"]");
        page.waitForSelector("#" + INSIGHTS_TAB + "-tab.active");
        page.waitForFunction("""
                () => {
                    const inViewport = el => {
                        const box = el.getBoundingClientRect();
                        return box.bottom > 0 && box.top < window.innerHeight;
                    };
                    const panels = Array.from(document.querySelectorAll('#insights-panels .pk-insight-panel'))
                        .filter(inViewport);
                    return panels.length > 0 && panels.every(panel =>
                        panel.querySelector('.pk-insight-empty')
                            || Array.from(panel.querySelectorAll('canvas')).some(canvas => canvas.width > 0));
                }
                """, null, new Page.WaitForFunctionOptions().setTimeout(15000));
        shoot(outputDir, "dashboard-" + INSIGHTS_TAB + "-" + theme);
    }

    /**
     * Blocks until level 0 of the Insights ring holds {@link #MIN_INSIGHTS_SAMPLES}
     * samples, asking the same endpoint the tab itself loads its history from. The budget
     * is derived from the configured level-0 interval rather than assumed: a dozen ticks
     * plus one for the one in flight, so a profile with a slower cadence waits
     * proportionally longer instead of failing.
     *
     * <p>Polled with {@code page.evaluate} in a plain loop, not {@code waitForFunction}:
     * the predicate has to await a fetch, and waitForFunction does not await an async
     * predicate - the pending Promise itself is truthy, so such a wait "passes" on its
     * first poll.
     */
    private void waitForInsightsHistory() {
        Number intervalMs = (Number) page.evaluate("""
                async () => {
                    const response = await fetch('/peekaboot/api/insights/config');
                    return (await response.json()).levels[0].intervalMs;
                }
                """);
        long deadline = System.currentTimeMillis() + intervalMs.longValue() * (MIN_INSIGHTS_SAMPLES + 1);
        while (true) {
            Number count = (Number) page.evaluate("""
                    async () => {
                        const response = await fetch('/peekaboot/api/insights/data?level=0');
                        return (await response.json()).count;
                    }
                    """);
            if (count.intValue() >= MIN_INSIGHTS_SAMPLES) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("insights level 0 still holds only " + count + " of the "
                        + MIN_INSIGHTS_SAMPLES + " samples required for a chart worth photographing");
            }
            page.waitForTimeout(1000);
        }
    }

    /**
     * Expands {@code tabId}'s masked-value group (see {@link #MASKED_GROUP_HEADER_SELECTOR})
     * before its screenshot, if that tab has one. A no-op for every tab but Environment
     * and Config.
     */
    private void expandMaskedGroupIfPresent(String tabId) {
        String headerSelector = MASKED_GROUP_HEADER_SELECTOR.get(tabId);
        if (headerSelector == null) {
            return;
        }
        page.click(headerSelector);
        page.waitForSelector(headerSelector + "[aria-expanded=\"true\"]");
    }

    /**
     * Captures the revealed counterpart of {@link #expandMaskedGroupIfPresent}'s masked
     * shot for Environment and Config - a no-op for every other tab. Clicks the "Show
     * secrets" control, waits for the one property this tool is permitted to reveal (see
     * {@link #REVEALED_FIXTURE_VALUE}) to actually render unmasked, shoots, then clicks
     * the control again and waits for the mask to return before this method returns - the
     * reveal flag is shared page-wide (see {@link #REVEAL_BUTTON_SELECTOR}'s doc comment),
     * so leaving it on would reveal Config's fixture too early if this tab is Environment,
     * or bleed into a later theme's masked screenshots otherwise.
     *
     * <p>This only ever reveals the {@code spring.datasource.password} fixture inside the
     * one group {@link #MASKED_GROUP_HEADER_SELECTOR} already scoped to - never
     * {@code systemProperties}/{@code systemEnvironment}, which that selector deliberately
     * excludes. See its doc comment, and {@code docs/IMPROVEMENTS.md} §4, for why.
     */
    private void captureRevealedGroupIfPresent(Path outputDir, String tabId, String theme) {
        String buttonSelector = REVEAL_BUTTON_SELECTOR.get(tabId);
        if (buttonSelector == null) {
            return;
        }

        page.click(buttonSelector);
        waitForRevealedRowValue(tabId, REVEALED_FIXTURE_VALUE);
        shoot(outputDir, "dashboard-" + tabId + "-revealed-" + theme);

        page.click(buttonSelector);
        waitForRevealedRowValue(tabId, MASKED_VALUE);
    }

    /**
     * Waits for the {@link #REVEALED_ROW_KEY} row inside {@link #REVEALED_ROW_CONTAINER_SELECTOR}
     * to render {@code expected} as its value. Mirrors
     * {@code UnmaskingControlEnabledIT.waitForConfigPasswordValue}'s own lookup-by-key
     * approach rather than a CSS value selector, since {@code kvRow} renders both the key
     * and the value as plain text nodes with nothing to select the value by other than its
     * sibling key.
     */
    private void waitForRevealedRowValue(String tabId, String expected) {
        page.waitForFunction(
                """
                ([container, key, expected]) => {
                    const row = Array.from(document.querySelectorAll(container + ' .pk-kv'))
                        .find(r => r.querySelector('.pk-kv__key').textContent === key);
                    return row && row.querySelector('.pk-kv__value').textContent === expected;
                }
                """, List.of(REVEALED_ROW_CONTAINER_SELECTOR.get(tabId), REVEALED_ROW_KEY.get(tabId), expected));
    }

    private void captureToolbar(Path outputDir, String theme) {
        newThemedPage(theme);
        page.navigate(baseUrl + "/orders");
        page.waitForSelector("#peekaboot-toolbar-host");
        // The bar shows a "loading" placeholder in its metrics area while it fetches the
        // request's own trace insights; wait for that to resolve so the screenshot shows
        // the real duration/query/log metrics instead of a spinner.
        page.waitForFunction("() => !document.getElementById('peekaboot-toolbar-host').shadowRoot"
                + ".querySelector('.pk-toolbar__loading')");
        shoot(outputDir, "toolbar-collapsed-" + theme);
    }

    private void newThemedPage(String theme) {
        if (page != null) {
            page.context().close();
        }
        page = browserContextPage();
        setStoredTheme(theme);
    }

    private void shoot(Path outputDir, String name) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(outputDir.resolve(name + ".png"))
                .setFullPage(false));
    }

    /**
     * Gives the Traces tab a real mix to list - an ordinary request, a slow report, a
     * failing request and, last, the deliberately heavy N+1 /orders trace that
     * peekaboot.tracing.max-spans-per-trace exists to keep intact. Its traceId is read
     * straight from that response's own toolbar payload, the same JSON the toolbar itself
     * renders from, so {@link #flagshipTraceId} names the real trace regardless of what
     * else the dashboard's own tabs cause to be captured afterward.
     */
    private void generateTraffic() {
        page.navigate(baseUrl + "/persons");
        page.navigate(baseUrl + "/api/orders/1/report");
        page.navigate(baseUrl + "/boom");
        page.navigate(baseUrl + "/orders");
        flagshipTraceId = (String) page.evaluate(
                "() => JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent).traceId");
        if (flagshipTraceId == null || flagshipTraceId.isBlank()) {
            throw new IllegalStateException("no traceId on the /orders toolbar payload - "
                    + "cannot deep-link the flagship trace-detail screenshot");
        }
        // The toolbar payload above is read the instant the response committed; the
        // /orders trace's ~80+ spans arrive at the store asynchronously afterward via
        // Spring's event listener. No element on this page flips state when that finishes,
        // so there's nothing to waitForSelector/waitForFunction on - a fixed pause is the
        // only option to let span capture settle before the dashboard/trace-detail
        // screenshots below read this trace back.
        page.waitForTimeout(500);
    }
}
