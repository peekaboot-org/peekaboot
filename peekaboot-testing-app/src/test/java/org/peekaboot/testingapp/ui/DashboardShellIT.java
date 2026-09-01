package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

class DashboardShellIT extends PlaywrightTestBase {

    @Test
    void dashboardRendersHeaderAndDefaultTab() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        // openDashboard() guarantees data has arrived, so this proves a real render
        // (the artifact name from build-info.properties), not just an empty container.
        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    /**
     * The header is a neutral --pk-bg surface, so the wordmark takes --pk-text-strong
     * rather than an on-fill ink. It used to be a bare `color: white` on a --pk-primary
     * slab, which was 5.17:1 while --pk-primary was a blue but only 2.53:1 in dark theme.
     * Pinning the literal resolved colour (matching TraceOverlayIT's contrast
     * regression tests) catches a revert to any hardcoded colour, which "looks right" in
     * one theme and would pass every other test here.
     *
     * Both tests deliberately set the stored preference to the opposite of the emulated
     * OS scheme, so they also prove the stored theme still wins over prefers-color-scheme.
     */
    @Test
    void headerTextIsContrastTunedInLightTheme() {
        setStoredTheme("light");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        openDashboard();

        assertThat(cssVar("h1", "color")).isEqualTo("rgb(17, 24, 39)");
    }

    @Test
    void headerTextIsContrastTunedInDarkTheme() {
        setStoredTheme("dark");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
        openDashboard();

        assertThat(cssVar("h1", "color")).isEqualTo("rgb(240, 246, 252)");
    }

    /**
     * The mark's magnifier is a dark slate that measures 1.4:1 on the dark theme's
     * --pk-bg, so the header swaps to a light-magnifier variant of the artwork. Nothing
     * about that swap is visible to the other tests - a broken selector or a renamed file
     * would silently leave the dark theme showing an all-but-invisible logo - so assert
     * the resolved background-image directly. Split per theme rather than toggling within
     * one test: setStoredTheme() stacks an init script that re-runs on every navigation.
     */
    @Test
    void headerLogoUsesTheFullColourMarkInLightTheme() {
        setStoredTheme("light");
        openDashboard();

        assertThat(cssVar(".pk-header__logo", "background-image"))
                .contains("logo-mark.png")
                .doesNotContain("logo-mark-dark.png");
    }

    @Test
    void headerLogoUsesTheLightMagnifierMarkInDarkTheme() {
        setStoredTheme("dark");
        openDashboard();

        assertThat(cssVar(".pk-header__logo", "background-image")).contains("logo-mark-dark.png");
    }

    /**
     * The icon set is referenced only from CSS url() and <link rel="icon">, so a path
     * typo or a packaging change that stopped shipping binaries from the frontend module
     * would fail silently - no console error the other tests would notice, just a missing
     * favicon and an empty logo box.
     */
    @Test
    void iconAssetsAreServed() {
        for (String asset :
                java.util.List.of("favicon-16.png", "favicon-32.png", "logo-mark.png", "logo-mark-dark.png")) {
            APIResponse response = page.request().get(baseUrl + "/peekaboot/ui/assets/" + asset);
            assertThat(response.status()).as(asset).isEqualTo(200);
        }
    }

    @Test
    void toolbarIsInjectedIntoApplicationPages() {
        openPersonsPage();

        // isVisible() on the host element only proves the injected <div> exists; the
        // toolbar itself lives inside its shadow root, so require that to be attached.
        boolean shadowRootAttached =
                (boolean) page.evaluate("document.getElementById('peekaboot-toolbar-host').shadowRoot !== null");
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

        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay')"
                + "?.shadowRoot?.querySelector('.pk-overlay__error')");
    }

    /**
     * main.js reads locale/timezone preferences from localStorage during module
     * evaluation (before initTheme()/initTabs() etc. even run). In a storage-blocked
     * context (private browsing, some embedded/iframe contexts, strict cookie policies)
     * an unguarded read throws at import time and the whole module fails to evaluate -
     * no DOMContentLoaded listener ever gets attached, so the dashboard never boots.
     * Mirrors ThemeResolutionIT's resolveThemeDegradesToOsPreferenceWhenLocalStorageThrows.
     */
    @Test
    void dashboardStillBootsWhenLocalStorageReadThrows() {
        page.addInitScript("localStorage.getItem = () => { throw new Error('storage blocked'); };");

        openDashboard();

        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    /**
     * Changing the locale selector writes the new preference to localStorage before
     * re-fetching data. An unguarded write throwing there must not stop the fetch (or
     * escape as an uncaught exception from the change handler) - the dashboard should
     * simply fail to persist the preference and carry on. Waiting for the re-fetch's
     * response (carrying the new locale) is positive proof the handler ran past the
     * throwing write, not just that nothing crashed synchronously.
     */
    @Test
    void dashboardSurvivesLocalStorageWriteThrowingOnLocaleChange() {
        page.addInitScript("localStorage.setItem = () => { throw new Error('storage blocked'); };");
        java.util.List<String> pageErrors = new java.util.ArrayList<>();
        page.onPageError(pageErrors::add);
        openDashboard();

        page.waitForResponse(
                response -> response.url().contains("locale=de-DE"),
                () -> page.selectOption("#locale-select", "de-DE"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible("#error")).isFalse();
    }
}
