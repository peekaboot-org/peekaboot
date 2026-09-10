package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.ZoneId;
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
        emulateOsColorScheme(ColorScheme.DARK);
        openDashboard();

        assertThat(cssVar("h1", "color")).isEqualTo("rgb(17, 24, 39)");
    }

    @Test
    void headerTextIsContrastTunedInDarkTheme() {
        setStoredTheme("dark");
        emulateOsColorScheme(ColorScheme.LIGHT);
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
        dashboard.openTab("insights");
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
     * No build step means main.js is one module script over a graph of forty-odd separate
     * fetches, and losing any one of them leaves the graph unevaluated: nothing hides the
     * loading placeholder and nothing raises the banner, so the page sits on the spinner for
     * good. Chromium drops every request in flight with ERR_NETWORK_CHANGED whenever the
     * host's network configuration changes - a container taking a veth interface up or down
     * is enough - so this is a transient a page meets with nothing broken, and one reload
     * fetches the whole graph again. Here the module never arrives, so the reload cannot help
     * and the reader has to be told.
     */
    @Test
    void aScriptThatNeverArrivesRaisesTheBannerAfterTheReloadFailsToo() {
        page.route("**/peekaboot/ui/dashboard/tabs/meters.js", route -> route.abort());

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

        page.waitForSelector("#error:not(.hidden)");
        assertThat(page.textContent("#error .message")).contains("could not start");
        assertThat(page.isVisible("#loading")).isFalse();
    }

    /**
     * The transient itself: one module lost on the first attempt and served on the next. The
     * dashboard reloads itself out of it, which is what keeps every entry point covered - the
     * deep-link tests navigate to the page directly rather than through openDashboard().
     * Two main-frame navigations for the one this test asked for is the reload.
     */
    @Test
    void theDashboardReloadsItselfWhenAScriptIsLostOnTheFirstTry() {
        List<String> navigations = new ArrayList<>();
        page.onFrameNavigated(frame -> {
            if (frame.equals(page.mainFrame())) {
                navigations.add(frame.url());
            }
        });
        page.route(
                "**/peekaboot/ui/dashboard/tabs/meters.js",
                route -> route.abort(),
                new Page.RouteOptions().setTimes(1));

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        page.waitForSelector("#build-info > *");

        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
        assertThat(navigations).hasSize(2);
    }

    /**
     * The reload cannot wait for the load event alone. A half-connected network that loses a
     * module usually leaves another request hanging too, and the document then sits at
     * readyState "interactive" for good, so load never fires. Here the module graph fails and
     * the header logo never answers, which leaves the bounded wait as the only thing that can
     * start the reload.
     */
    @Test
    void theDashboardReloadsEvenWhenAnotherSubresourceNeverAnswers() {
        setStoredTheme("light");
        // Left unanswered for the whole test, so no document here ever fires its load event.
        page.route("**/peekaboot/ui/assets/logo-mark.png", route -> {});
        page.route(
                "**/peekaboot/ui/dashboard/tabs/meters.js",
                route -> route.abort(),
                new Page.RouteOptions().setTimes(1));

        page.navigate(
                baseUrl + "/peekaboot/ui/dashboard/index.html",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForSelector("#build-info > *");

        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    /**
     * A marker is written before the reload commits, so a reload that never navigates - the
     * tab went offline, or was closed in between - leaves one behind. Honouring it forever
     * would spend the next episode's one retry on a reload that never happened, so it counts
     * only while it is recent.
     */
    @Test
    void aStaleRetryMarkerDoesNotSpendTheNextFailuresReload() {
        page.addInitScript("sessionStorage.setItem('peekaboot-dashboard-retried', String(Date.now() - 120000));");
        page.route(
                "**/peekaboot/ui/dashboard/tabs/meters.js",
                route -> route.abort(),
                new Page.RouteOptions().setTimes(1));

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        page.waitForSelector("#build-info > *");

        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
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

    /**
     * The banner a failed refresh raises, and the only way back out of it. Aborting the data
     * request is a real refusal by Chromium's network stack, and the dashboard is loaded
     * before the route is installed, so what fails is the refresh rather than the boot.
     */
    @Test
    void aFailedRefreshRaisesTheErrorBannerAndTheCloseButtonDismissesIt() {
        openDashboard();
        page.route("**/peekaboot/api/actuator/all/insights**", route -> route.abort());

        page.click("#refresh-btn");

        page.waitForSelector("#error:not(.hidden)");
        assertThat(page.textContent("#error .message")).startsWith("Failed to load data:");

        page.click("#error-close");

        page.waitForSelector("#error", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.isVisible("#error")).isFalse();
    }

    /**
     * The timezone toggle switches which zone every rendered timestamp is read in, says which
     * one is showing, and remembers the choice. The readout has to become the server's real
     * zone from the payload - "Unknown" is what it says when nothing arrived - and the button
     * has to name the direction, since "Server"/"Browser" alone is not a usable button name.
     * The two zone ids are not compared: the server here is this same JVM.
     */
    @Test
    void theTimezoneToggleSwitchesToServerTimeAndPersists() {
        openDashboard();
        assertThat(page.textContent("#timezone-label")).isEqualTo("Browser");

        page.click("#timezone-toggle");

        assertThat(page.textContent("#timezone-label")).isEqualTo("Server");
        String serverZone = page.textContent("#tz-info");
        assertThat(serverZone).isEqualTo(ZoneId.systemDefault().getId());
        assertThat(page.getAttribute("#timezone-toggle", "aria-label")).contains("Switch to browser timezone");

        page.reload();
        page.waitForSelector("#loading", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.textContent("#timezone-label"))
                .as("the choice is kept under peekaboot-use-server-tz")
                .isEqualTo("Server");
        assertThat(page.textContent("#tz-info")).isEqualTo(serverZone);
    }

    /**
     * Choosing a locale re-fetches with it and re-renders the dates in it. The Build card's
     * timestamp is the assertion: German renders the month as a number where en-US spells it,
     * so a locale that reached the request but not the render fails here.
     */
    @Test
    void changingTheLocaleRerendersDatesInIt() {
        openDashboard();
        page.waitForSelector("#build-info .pk-kv");
        String english = dashboard.kvValue("#build-info", "Built");

        page.waitForResponse(
                response -> response.url().contains("locale=de-DE") && response.status() == 200,
                () -> page.selectOption("#locale-select", "de-DE"));
        page.waitForFunction(
                "(before) => document.querySelector('#build-info')?.textContent.includes(before) === false", english);

        assertThat(dashboard.kvValue("#build-info", "Built"))
                .as("the same instant, rendered in the chosen locale")
                .isNotEqualTo(english);
    }
}
