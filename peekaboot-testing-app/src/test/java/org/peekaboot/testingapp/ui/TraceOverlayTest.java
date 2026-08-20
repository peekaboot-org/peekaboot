package org.peekaboot.testingapp.ui;

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
    }

    private String overlayVar(String property) {
        return (String) page.evaluate(
                "prop => getComputedStyle(document.getElementById('peekaboot-trace-overlay')"
              + ".shadowRoot.querySelector('.pk-overlay')).getPropertyValue(prop).trim()", property);
    }

    /** The defect that motivated this work: a light dashboard opening a dark overlay. */
    @Test
    void overlayIsLightWhenTheStoredPreferenceIsLight() {
        setStoredTheme("light");
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void overlayIsDarkWhenTheStoredPreferenceIsDark() {
        setStoredTheme("dark");
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#0d1117");
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
