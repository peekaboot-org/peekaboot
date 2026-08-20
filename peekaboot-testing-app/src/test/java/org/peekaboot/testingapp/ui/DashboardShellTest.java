package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardShellTest extends PlaywrightTestBase {

    @Test
    void dashboardRendersHeaderAndDefaultTab() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        // openDashboard() guarantees data has arrived, so this proves a real render
        // (the artifact name from build-info.properties), not just an empty container.
        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    /**
     * The header used a bare `color: white`, which is 5.17:1 on light theme's
     * --pk-primary but only 2.53:1 on dark theme's (lighter, --primary is a blue tuned
     * for dark backgrounds) --pk-primary - well under AA. --pk-on-primary is tuned per
     * theme for exactly this fill; pinning the literal resolved colour (matching
     * TraceOverlayTest's contrast regression tests) catches a revert back to plain
     * white, which "looks right" in light theme and would pass every other test here.
     */
    @Test
    void headerTextIsContrastTunedInLightTheme() {
        setStoredTheme("light");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        openDashboard();

        assertThat(cssVar("h1", "color")).isEqualTo("rgb(255, 255, 255)");
    }

    @Test
    void headerTextIsContrastTunedInDarkTheme() {
        setStoredTheme("dark");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
        openDashboard();

        assertThat(cssVar("h1", "color")).isEqualTo("rgb(13, 17, 23)");
    }

    @Test
    void toolbarIsInjectedIntoApplicationPages() {
        openPersonsPage();

        // isVisible() on the host element only proves the injected <div> exists; the
        // toolbar itself lives inside its shadow root, so require that to be attached.
        boolean shadowRootAttached = (boolean) page.evaluate(
                "document.getElementById('peekaboot-toolbar-host').shadowRoot !== null");
        assertThat(shadowRootAttached).isTrue();
    }

    /**
     * main.js imports {open, close} directly from trace-detail.js (no more
     * window.PeekabootTraceDetail global - see trace-detail.js's header comment). Its
     * hash-routing handles a deep link to a specific trace (`#traces/<id>`) by calling that
     * imported open() itself, before the traces tab that lists them exists (Task 15) - so a
     * direct navigation to such a link is enough to prove the import actually loaded and
     * ran, independent of whether the trace id resolves to anything real.
     *
     * Waiting only for "#peekaboot-trace-overlay" to exist would prove less than it looks
     * like: openTraceDetail() appends that host synchronously, before any fetch even
     * starts, so it passes even if render() itself is broken. "deadbeef" is not a real
     * trace id, so the fetch deterministically 404s - waiting for the resulting
     * ".pk-overlay__error" instead proves fetchAndRender() actually ran to completion.
     */
    @Test
    void dashboardLoadsTheTraceDetailOverlayModule() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/deadbeef");

        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay')"
              + "?.shadowRoot?.querySelector('.pk-overlay__error')");
    }
}
