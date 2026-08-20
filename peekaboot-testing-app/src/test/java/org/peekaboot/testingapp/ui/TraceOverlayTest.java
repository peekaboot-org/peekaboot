package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
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
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
    }

    private String overlayVar(String property) {
        return (String) page.evaluate(
                "prop => getComputedStyle(document.getElementById('peekaboot-trace-overlay')"
              + ".shadowRoot.querySelector('.pk-overlay')).getPropertyValue(prop).trim()", property);
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
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
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
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                    + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-log__span')",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                    + ".querySelector('.pk-log__span').click()");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-logs-filter-span')");

        String color = (String) page.evaluate(
                "() => getComputedStyle(document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-logs-filter-span')).color");

        assertThat(color).isEqualTo("rgb(255, 255, 255)");
    }

    @Test
    void overlayShowsSpansTabByDefault() {
        openOverlayFromToolbar();

        String selected = (String) page.evaluate(
                "() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
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

    /**
     * window.PeekabootTraceDetail stays assigned - dashboard/peekaboot.js is still a classic
     * script (no imports) and calls it directly; see DashboardShellTest.
     * dashboardLoadsTheTraceDetailOverlayModule for that side of the contract.
     */
    @Test
    void windowPeekabootTraceDetailStillWorksForTheClassicDashboardScript() {
        openOverlayFromToolbar();

        assertThat(page.evaluate("() => typeof window.PeekabootTraceDetail")).isEqualTo("object");
        assertThat(page.evaluate("() => typeof window.PeekabootTraceDetail.open")).isEqualTo("function");
        assertThat(page.evaluate("() => typeof window.PeekabootTraceDetail.close")).isEqualTo("function");
    }

    /** role=dialog + aria-modal, and a real accessible name, not just visual chrome. */
    @Test
    void overlayExposesDialogSemantics() {
        openOverlayFromToolbar();

        String role = (String) page.evaluate(
                "() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-overlay').getAttribute('role')");
        String ariaModal = (String) page.evaluate(
                "() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-overlay').getAttribute('aria-modal')");
        String accessibleName = (String) page.evaluate(
                "() => { const el = document.getElementById('peekaboot-trace-overlay').shadowRoot"
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
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                    + ".shadowRoot.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");
        page.waitForSelector("#peekaboot-trace-overlay");
        // container.focus() only happens once render() actually runs (after the trace
        // fetch and shared stylesheets both resolve) - wait for real content so the
        // assertion below cannot race a still-loading overlay.
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
              + ".querySelector('.pk-tab')",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        boolean focusIsInsideOverlay = (Boolean) page.evaluate(
                "() => { const host = document.getElementById('peekaboot-trace-overlay');"
              + " return host.shadowRoot.activeElement !== null; }");
        assertThat(focusIsInsideOverlay).isTrue();

        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);

        boolean focusIsBackOnTheInvoker = (Boolean) page.evaluate(
                "() => document.getElementById('peekaboot-toolbar-host').shadowRoot.activeElement"
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

        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay')"
              + ".shadowRoot.querySelector('.pk-overlay__error')");
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                    + ".querySelector('.pk-overlay__error button').click()");

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }
}
