package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real trace-detail overlay served by the running app in a real browser.
 * Migrated onto the shared design system alongside the toolbar (see ToolbarTest) - the
 * overlay used to hardcode a dark-only palette on :host with no theme awareness at all,
 * so a light dashboard opened a hard-dark fullscreen overlay. That is the specific defect
 * overlayIsLightWhenTheStoredPreferenceIsLight proves fixed.
 */
class TraceOverlayTest extends PlaywrightTestBase {

    private void openOverlayFromToolbar() {
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        // #peekaboot-trace-overlay (the host) exists as soon as openTraceDetail() creates
        // it - well before fetchAndRender() replaces the loading placeholder with either
        // the success render() (which registers the ESC handler) or the error state. This
        // helper is used by both paths (see closeButtonDismissesTheOverlayOnTheErrorPath),
        // so it waits for the loading placeholder to be gone rather than for a
        // success-only element, and cannot race either outcome.
        page.waitForFunction(
                "() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay__loading')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
    }

    private String overlayVar(String property) {
        return (String) page.evaluate(
                "prop => getComputedStyle(document.getElementById('peekaboot-trace-overlay')"
                        + ".shadowRoot.querySelector('.pk-overlay')).getPropertyValue(prop).trim()",
                property);
    }

    /**
     * Headless Chromium's own default is prefers-color-scheme: light, so a naive
     * "storage wins" test in the light direction would pass even with resolveTheme()/
     * applyTheme() deleted entirely - light is also tokens.css's bare :root,:host default.
     * Forcing the OS preference to the opposite of what's stored (mirroring ToolbarTest)
     * makes each test fail if the stored preference ever stops taking priority.
     */
    private void emulateOppositeOsPreference(ColorScheme osPreference) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(osPreference));
    }

    /** The defect that motivated this work: a light dashboard opening a dark overlay. */
    @Test
    void overlayIsLightWhenTheStoredPreferenceIsLight() {
        setStoredTheme("light");
        emulateOppositeOsPreference(ColorScheme.DARK);
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void overlayIsDarkWhenTheStoredPreferenceIsDark() {
        setStoredTheme("dark");
        emulateOppositeOsPreference(ColorScheme.LIGHT);
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#0d1117");
    }

    /**
     * Regression guard for a real defect an earlier review caught: fills that reuse
     * --pk-primary (this chip, the gantt "server" kind badge) or --pk-success (the
     * result-set row-count badge) for their background had their foreground accidentally
     * set to --pk-text-strong instead of the contrast-tuned --pk-on-primary/--pk-on-success
     * that components.css's .pk-badge already uses for the same fills - near-white text on
     * light-blue/light-green at ~2.3:1 in dark mode, where the pre-migration hardcoded
     * #000 gave 8.2-8.3:1. Pins the literal resolved colour rather than comparing against
     * the --pk-on-primary token itself, which would pass even if both sides regressed back
     * to the same wrong token.
     *
     * The pinned value is dark ink in both themes now that --pk-primary is the brand
     * green: white on it measures 2.61:1, so the light theme can no longer get away
     * with the plain white it used while --pk-primary was a blue.
     *
     * Drives a real ERROR log entry (matching ToolbarTest's
     * toolbarShowsErrorLogCountWhenRequestLogsAnError) rather than the gantt "server" kind
     * badge: this test app's real request-capture path (RequestCaptureFilter /
     * TracingHandlerInterceptor) never tags the root span with an OpenTelemetry SERVER
     * kind - only OtelSpanExporter does that - so .pk-gantt-kind.server never actually
     * renders here, and a result-set row-count badge needs a JDBC instrumentation detail
     * this test has no reason to depend on. The logs-tab span-filter chip needs only one
     * real log entry attached to the trace, which /?error=true reliably provides.
     */
    @Test
    void logsFilterChipUsesTheContrastTunedForeground() {
        setStoredTheme("light");
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        // #peekaboot-trace-overlay exists as soon as openTraceDetail() creates the host -
        // well before render() builds the tab strip, which only happens once the trace
        // fetch and the shared stylesheets have both resolved. Wait for the real tab
        // before clicking it.
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-tab[data-tab=\"logs\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-log__span')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-log__span').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span')");

        String color = (String)
                page.evaluate("() => getComputedStyle(document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-logs-filter-span')).color");

        assertThat(color).isEqualTo("rgb(13, 17, 23)");
    }

    @Test
    void overlayShowsSpansTabByDefault() {
        openOverlayFromToolbar();

        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("spans");
    }

    @Test
    void escapeClosesTheOverlay() {
        openOverlayFromToolbar();

        page.keyboard().press("Escape");

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }

    /** role=dialog + aria-modal, and a real accessible name, not just visual chrome. */
    @Test
    void overlayExposesDialogSemantics() {
        openOverlayFromToolbar();

        String role = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay').getAttribute('role')");
        String ariaModal = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay').getAttribute('aria-modal')");
        String accessibleName = (String)
                page.evaluate("() => { const el = document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay'); const labelledBy = el.getAttribute('aria-labelledby');"
                        + " return labelledBy ? el.getRootNode().getElementById(labelledBy).textContent.trim()"
                        + " : el.getAttribute('aria-label'); }");

        assertThat(role).isEqualTo("dialog");
        assertThat(ariaModal).isEqualTo("true");
        assertThat(accessibleName).isNotBlank();
    }

    /**
     * Opening the overlay from a keyboard-focused toolbar button must move focus into the
     * overlay, and closing it (ESC) must return focus to that same button - otherwise a
     * keyboard user opens a fullscreen overlay while focus silently stays behind it.
     */
    @Test
    void focusMovesIntoTheOverlayOnOpenAndReturnsToTheInvokerOnClose() {
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");
        page.waitForSelector("#peekaboot-trace-overlay");
        // container.focus() only happens once render() actually runs (after the trace
        // fetch and shared stylesheets both resolve) - wait for real content so the
        // assertion below cannot race a still-loading overlay.
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot" + ".querySelector('.pk-tab')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        boolean focusIsInsideOverlay =
                (Boolean) page.evaluate("() => { const host = document.getElementById('peekaboot-trace-overlay');"
                        + " return host.shadowRoot.activeElement !== null; }");
        assertThat(focusIsInsideOverlay).isTrue();

        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);

        boolean focusIsBackOnTheInvoker = (Boolean)
                page.evaluate("() => document.getElementById('peekaboot-toolbar-host').shadowRoot.activeElement"
                        + "?.classList.contains('pk-toolbar__open') ?? false");
        assertThat(focusIsBackOnTheInvoker).isTrue();
    }

    /**
     * Forces the overlay's error path (a real network failure, not a mocked response) and
     * proves its Close button actually works. Pre-existing defect: the button called
     * this.closest('#peekaboot-trace-overlay').remove() from inside the shadow root, where
     * closest() cannot cross the shadow boundary, so the button threw and did nothing.
     */
    @Test
    void closeButtonDismissesTheOverlayOnTheErrorPath() {
        page.route("**/api/traces/*/insights", route -> route.abort());
        openOverlayFromToolbar();

        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('.pk-overlay__error')");
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay__error button').click()");

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }

    /**
     * The overlay's own four-tab strip is built by the same shared tabStrip() helper
     * as the dashboard's - a real ArrowRight keypress (not a direct handler call) must
     * move both the DOM focus and the aria-selected tab from Spans to Queries.
     */
    @Test
    void overlayTabStripIsKeyboardNavigable() {
        openOverlayFromToolbar();
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"spans\"]').focus()");

        page.keyboard().press("ArrowRight");

        String focused =
                (String) page.evaluate("() => { const host = document.getElementById('peekaboot-trace-overlay');"
                        + " return host.shadowRoot.activeElement?.dataset.tab; }");
        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");

        assertThat(focused).isEqualTo("queries");
        assertThat(selected).isEqualTo("queries");

        String content = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content').innerHTML");
        assertThat(content).isNotEmpty();
    }

    /** Only the selected main tab stays in the tab order - roving tabindex. */
    @Test
    void onlyTheSelectedOverlayTabIsInTheTabOrder() {
        openOverlayFromToolbar();

        Object selectedTabIndex = page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').tabIndex");
        Object otherTabIndex = page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"false\"]').tabIndex");

        assertThat(selectedTabIndex).isEqualTo(0);
        assertThat(otherTabIndex).isEqualTo(-1);
    }

    /**
     * Inspects the real accessibility tree for the overlay's strip too - confirms it
     * exposes as an actual tablist with the right tabs and selected state, same as
     * the dashboard's equivalent check. This alone does NOT prove TABS.count is
     * load-bearing: the pre-change hand-rolled markup rendered an identical
     * "Queries 1" from its own separately-computed queryCount, so this snapshot
     * would very likely have passed before this change too. The actual proof is
     * that render() no longer has that duplicate template text at all - a
     * code-level fact (see the task report's TDD section for the discriminating
     * evidence).
     */
    @Test
    void overlayTabStripExposesAsARealTablistInTheAccessibilityTree() {
        // This is the one test that asserts the QUERIES COUNT rendered into the tab
        // strip, and that count races span ingestion: the toolbar shows the trace id
        // as soon as the response arrives, but the SQL query span reaches the trace
        // store asynchronously after the response is written. On a fast machine the
        // store wins; on slower ones (observed on macOS) the overlay fetch can read
        // the trace before its query landed and render "Queries 0". Wait for the
        // backend to actually serve the query before opening the overlay - the same
        // endpoint and field the overlay's TABS.count reads (trace.queries).
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.waitForFunction(
                "async () => {"
                        + "const id = document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-trace').textContent.trim();"
                        + "const response = await fetch('/peekaboot/api/traces/' + id + '/insights');"
                        + "if (!response.ok) return false;"
                        + "const trace = await response.json();"
                        + "return (trace.queries || []).length > 0;"
                        + "}",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        // Not openOverlayFromToolbar(): that helper re-navigates, which would mint a
        // fresh trace and reopen the very race waited out above. Open the overlay for
        // the already-verified trace directly.
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        page.waitForFunction(
                "() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay__loading')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"queries\"]').click()");

        Locator tablist = page.locator("#peekaboot-trace-overlay .pk-overlay__container > .pk-tabs");
        String snapshot = tablist.ariaSnapshot();

        assertThat(snapshot).contains("tablist");
        assertThat(snapshot).contains("\"Spans\"");
        // The " 1" is the queries count TABS.count(trace) computes for this real trace -
        // pins that count is actually rendered into the tab, not just present in TABS.
        assertThat(snapshot).contains("\"Queries 1\" [selected]");
    }

    /**
     * The Request tab's own overview/request-headers/response-headers sub-tabs reuse
     * the same shared tabStrip() helper as the main strips - migrated off hand-rolled
     * click wiring (and a bespoke data-subtab attribute) alongside them, since it was
     * the same missing-roving-tabindex defect.
     */
    @Test
    void requestSubTabsAreKeyboardNavigable() {
        openOverlayFromToolbar();
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"request\"]').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[data-tab=\"overview\"]')");
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[data-tab=\"overview\"]').focus()");

        page.keyboard().press("ArrowRight");

        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("request-headers");

        String content = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-request-subtab-content').innerHTML");
        assertThat(content).isNotEmpty();
    }

    @Test
    void everyOverlayTabRendersContent() {
        openOverlayFromToolbar();

        for (String tab : java.util.List.of("request", "spans", "queries", "logs")) {
            page.evaluate(
                    "id => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                            + ".querySelector(`.pk-tab[data-tab=\"${id}\"]`).click()",
                    tab);

            String content =
                    (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                            + ".querySelector('#pk-tab-content').innerHTML");
            assertThat(content).as("tab %s renders something", tab).isNotEmpty();
        }
    }

    @Test
    void queriesTabListsTheJdbcQueryFromThePersonsPage() {
        openOverlayFromToolbar();
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"queries\"]').click()");

        String sql = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-query__sql')?.textContent ?? ''");
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("select");
    }

    /**
     * Regression test for a residual duplication: the SLOW label used to re-derive the
     * 100ms slow threshold with a bare literal (`duration > 100`) on the same line that
     * already computes `durationClass` from severity.js's durationSeverity() - now it just
     * reads durationClass. Imports queries.js directly (SharedModuleTest's pk-blank.html
     * pattern) rather than driving a real slow query through the app, and pins the exact
     * SLOW_MS boundary (100ms itself must NOT get the label; 101ms must) since an earlier
     * off-by-one at this same boundary was a real bug in this project (see severity.js's
     * own boundary test in SharedModuleTest).
     */
    @Test
    void queriesTabSlowLabelFollowsTheSharedSeverityThresholdAtTheBoundary() {
        page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");

        Object labels = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/trace-detail/tabs/queries.js');
                const container = document.createElement('div');
                m.render(container, {queries: [
                    {sql: 'SELECT 1', durationMs: 99,  dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 2', durationMs: 100, dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 3', durationMs: 101, dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 4', durationMs: 501, dbSystem: 'h2', rowCount: 1}
                ]});
                return Array.from(container.querySelectorAll('.pk-query__duration')).map(el => el.textContent);
            }
            """);

        @SuppressWarnings("unchecked")
        java.util.List<String> durationLabels = (java.util.List<String>) labels;
        assertThat(durationLabels).containsExactly("99ms", "100ms", "101ms SLOW", "501ms SLOW");
    }
}
