package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardShellIT extends PlaywrightTestBase {

    /**
     * The dashboard is the one Peekaboot surface a user reaches without already knowing the
     * project, so it carries the single link out to the documentation site. It opens in a new
     * tab because the dashboard holds live auto-refreshing state that navigating away discards.
     */
    @Test
    void footerLinksToTheDocumentationSite() {
        openDashboard();

        Locator docs = page.locator(".pk-footer a");

        assertThat(docs.textContent()).isEqualTo("Documentation");
        assertThat(docs.getAttribute("href")).isEqualTo("https://www.peekaboot.org/docs/");
        assertThat(docs.getAttribute("target")).isEqualTo("_blank");
        assertThat(docs.getAttribute("rel")).contains("noopener");
    }

    /**
     * --pk-primary-text, never --pk-primary: the fill green measures 2.61:1 as text (see
     * peekaboot-frontend/README.md). The underline is not decoration either - without it
     * colour alone would mark the link, which WCAG 1.4.1 does not allow.
     */
    @Test
    void documentationLinkIsDrawnInTheOnBackgroundGreenAndUnderlined() {
        setStoredTheme("light");
        openDashboard();

        assertThat(cssVar(".pk-footer a", "color")).isEqualTo("rgb(68, 119, 24)");
        assertThat(cssVar(".pk-footer a", "text-decoration-line")).isEqualTo("underline");
    }

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
     * rather than an on-fill ink - a bare `color: white` on a --pk-primary slab scores only
     * 2.53:1 in dark theme.
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
        for (String asset : List.of("favicon-16.png", "favicon-32.png", "logo-mark.png", "logo-mark-dark.png")) {
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
     * main.js imports {openTraceDetail, closeTraceDetail} directly from trace-detail.js (no
     * window.PeekabootTraceDetail global - see trace-detail.js's header comment). Its
     * hash-routing handles a deep link to a specific trace (`#traces/<id>`) by calling that
     * imported openTraceDetail() itself, without going through the traces tab - so a
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
     * A stale or hand-edited "type" param the backend does not know is dropped there
     * (the list shows every type), so the tab must drop it too - otherwise the banner
     * claims a filter is active over an unfiltered list. The corrected URL is part of the
     * contract: a link must never say "type=FOO" over a list that shows the default view.
     */
    @Test
    void aTracesDeepLinkWithAnUnknownTypeFallsBackToTheDefaultFilter() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?type=FOO");
        page.waitForSelector("#traces-tab.active");
        page.waitForSelector("#traces-list .pk-trace-item, #no-traces:not(.hidden)");

        assertThat(page.isVisible("#traces-active-filter")).isFalse();
        assertThat(page.locator("#traces-filter input:checked").count()).isZero();
        assertThat(page.url()).endsWith("#traces");
    }

    /** The backend folds the type's case; a lower-case link selects the same chip an upper-case one does. */
    @Test
    void aTracesDeepLinkTypeIsCaseInsensitive() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?type=scheduled_job");
        page.waitForSelector("#traces-tab.active");
        page.waitForSelector("#traces-list .pk-trace-item, #no-traces:not(.hidden)");

        @SuppressWarnings("unchecked")
        List<String> checked = (List<String>) page.evaluate(
                "() => [...document.querySelectorAll('#traces-filter input:checked')].map(cb => cb.value)");
        assertThat(checked).containsExactly("SCHEDULED_JOB");
        assertThat(page.url()).endsWith("#traces?type=SCHEDULED_JOB");
    }

    /**
     * The Insights stream reconnects on its own, and the reconnect re-snapshots every
     * loaded level. That resync is the one path that exists for an application that was
     * just restarting - exactly when a level request can still fail - so a failed
     * snapshot must be caught and logged, never escape as an unhandled rejection.
     *
     * <p>Both failures are real refusals by Chromium's network stack, not stubbed
     * responses: the stream's first connection is refused so that EventSource schedules
     * a reconnect of its own, and once the tab has loaded its level the data endpoint is
     * refused too, so the resync the reconnect triggers has to fail.
     */
    @Test
    void insightsResyncFailureIsCaughtRatherThanEscapingAsAPageError() {
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        page.route("**/api/insights/stream", route -> route.abort());

        openDashboard();
        page.click("#insights-tab-btn");
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
        page.route("**/api/insights/data*", route -> route.abort());

        page.waitForConsoleMessage(
                new Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg ->
                                msg.type().equals("warning") && msg.text().contains("resync"))
                        .setTimeout(15_000),
                () -> page.unroute("**/api/insights/stream"));

        assertThat(pageErrors).isEmpty();
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
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        openDashboard();

        page.waitForResponse(
                response -> response.url().contains("locale=de-DE"),
                () -> page.selectOption("#locale-select", "de-DE"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible("#error")).isFalse();
    }
}
