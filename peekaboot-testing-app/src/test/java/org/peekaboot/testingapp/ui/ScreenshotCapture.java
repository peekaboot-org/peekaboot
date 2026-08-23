package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

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
 */
// inheritProfiles = false: PlaywrightTestBase carries @ActiveProfiles("test") - merging
// that in (the default) would activate both "test" and "screenshots" and, since Spring
// Boot's last-profile-wins rule applies to the *resolved* order rather than declaration
// order, "test"'s H2/Flyway-disabled settings silently won over this profile's real
// Postgres config. Confirmed empirically: with inheritProfiles left at its default, the
// app started against H2's in-memory testdb rather than the Postgres compose service.
@ActiveProfiles(profiles = "screenshots", inheritProfiles = false)
class ScreenshotCapture extends PlaywrightTestBase {

    private static final String OUTPUT_PROPERTY = "peekaboot.screenshots.out";
    private static final int VIEWPORT_WIDTH = 1440;
    private static final int VIEWPORT_HEIGHT = 900;

    private static final List<String> DASHBOARD_TABS =
            List.of("dashboard", "environment", "flyway", "loggers", "config", "scheduled-tasks", "metrics", "traces");

    /**
     * The selector each tab waits for beyond "panel active" before it counts as rendered
     * with real data. Without this a tab can be photographed mid-fetch (an empty list),
     * since {@code #<id>-tab.active} only proves the panel is showing, not that its own
     * render() call has finished populating it.
     */
    private static final Map<String, String> TAB_READY_SELECTOR = Map.of(
            "dashboard", "#memory-info .pk-meter__fill",
            "environment", "#property-sources .pk-group__header",
            "flyway", "#flyway-timeline .pk-table tbody tr",
            "loggers", "#loggers-list .pk-group",
            "config", "#config-groups .pk-group__header",
            "scheduled-tasks", "#scheduled-tasks-groups .pk-group",
            "metrics", "#metrics-list .pk-group",
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
     * inside a collapsed group; see {@code UnmaskingControlEnabledTest}'s own doc comment),
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
     * The traceId of the flagship /orders N+1 request, captured straight from that
     * response's own toolbar payload in {@link #generateTraffic()} - see the field's use
     * in {@link #captureDashboardTabs} for why this is not simply "click the first item
     * in the traces list".
     */
    private String flagshipTraceId;

    @Override
    protected Page browserContextPage() {
        return browser.newContext(newContextOptions().setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
                .newPage();
    }

    @Test
    void captureEverySurfaceInBothThemes() throws Exception {
        Path outputDir = resolveOutputDir();

        for (String theme : List.of("light", "dark")) {
            captureDashboardTabs(outputDir, theme);
            captureToolbar(outputDir, theme);
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

        // Traces is already the active tab (last of DASHBOARD_TABS). Deep-link to the
        // flagship /orders trace by id rather than clicking the list's first (most recent)
        // entry: the Flyway tab's own migration-info lookup opens a JDBC connection outside
        // any request context, which Peekaboot captures as its own root-level "connection"
        // trace - created after generateTraffic() finishes and clicking through the Flyway
        // tab above, so it consistently sorts above /orders by the time this runs. Confirmed
        // by inspection of a first attempt at this that screenshotted that connection trace
        // instead of the intended N+1 example.
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
     * page.click() time out on a selector that quietly never existed. metrics and traces
     * are gated on GET /api/features; a silently absent button would otherwise look like an
     * ordinary Playwright timeout with no clue which tab caused it.
     */
    private void assertAllTabButtonsVisible() {
        List<String> missing = new ArrayList<>();
        for (String tabId : DASHBOARD_TABS) {
            if (!page.isVisible(".pk-tab[data-tab=\"" + tabId + "\"]")) {
                missing.add(tabId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("dashboard tab button(s) missing or hidden, cannot capture: " + missing);
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
     * {@code UnmaskingControlEnabledTest.waitForConfigPasswordValue}'s own lookup-by-key
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
