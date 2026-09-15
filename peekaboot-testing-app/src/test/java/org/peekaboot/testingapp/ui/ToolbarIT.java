package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real toolbar.js served by the running app in a real browser. Coverage comes
 * from real requests (a real DB-backed /persons request for query counts and controller name,
 * a real logged error for log counts, a real Server-Timing header for idle mode) rather than
 * stubbed fetch/setTimeout or mocked responses, per the project's no-mocking-in-e2e-tests
 * policy. Aborting a single, specific network request to
 * simulate a real fetch failure is not the same as mocking a fake response, so it is used for
 * the "pending" state test.
 */
class ToolbarIT extends PlaywrightTestBase {

    /** The OS preference is the opposite of what is stored (see emulateOsColorScheme), or the light case proves nothing. */
    @Test
    void toolbarFollowsTheStoredLightPreference() {
        setStoredTheme("light");
        emulateOsColorScheme(ColorScheme.DARK);
        openPersonsPage();

        assertThat(toolbar.cssVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void toolbarFollowsTheStoredDarkPreference() {
        setStoredTheme("dark");
        emulateOsColorScheme(ColorScheme.LIGHT);
        openPersonsPage();

        assertThat(toolbar.cssVar("--pk-bg")).isEqualTo("#0d1117");
    }

    @Test
    void toolbarShowsMethodPathAndStatusForTheRequest() {
        openPersonsPage();
        toolbar.waitUntil("root => root.querySelector('#pk-status').textContent.trim() !== ''");

        String status = toolbar.text("#pk-status");
        String path = toolbar.text("#pk-path");

        assertThat(status).isEqualTo("200");
        assertThat(path).isEqualTo("/persons");
    }

    /**
     * The bar runs inside pages Peekaboot does not own, so it may add nothing at all to their
     * window. Diffed against a snapshot taken before any page script ran, rather than probing
     * one name a leak would have to be called.
     */
    @Test
    void toolbarDoesNotLeakGlobals() {
        page.addInitScript("window.__pkGlobalsBeforeToolbar = Object.keys(window);");
        openPersonsPage();
        toolbar.traceId();

        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) page.evaluate("() => Object.keys(window)"
                + ".filter(key => key !== '__pkGlobalsBeforeToolbar'"
                + " && !window.__pkGlobalsBeforeToolbar.includes(key))");

        assertThat(added).isEmpty();
    }

    /**
     * The "open trace details" control is a real &lt;button&gt;: Enter must open the overlay
     * exactly like a mouse click, without any custom keydown handling (native button
     * activation dispatches a real click event that bubbles to the bar's listener).
     */
    @Test
    void theBarIsKeyboardOperableAndOpensTheOverlayOnEnter() {
        openPersonsPage();
        toolbar.traceId();

        toolbar.evaluate("root => root.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");

        overlay.awaitOpened();
        assertThat(page.isVisible(TraceOverlay.HOST)).isTrue();
    }

    /**
     * The dashboard link is a sibling of the "open trace details" button (not its
     * descendant), so it stays independently focusable, and its own stopPropagation keeps
     * a click on it from also triggering the bar's action. A negative assertion right after
     * the click would pass trivially - opening the overlay is async (dynamic import + a
     * module fetch), so the element couldn't exist yet even if the guard were deleted. Wait
     * for a bounded *absence* instead (expecting a timeout), then prove the negative wasn't
     * vacuous by clicking the real open button on the same page and confirming it does work.
     */
    @Test
    void theDashboardLinkDoesNotTriggerTheBarsOwnAction() {
        openPersonsPage();

        boolean linkIsFocusable = (Boolean)
                toolbar.evaluate(
                        "root => { const a = root.querySelector('.pk-toolbar a'); a.focus(); return root.activeElement === a; }");
        assertThat(linkIsFocusable).isTrue();

        toolbar.click(".pk-toolbar a");

        assertThatThrownBy(() -> page.waitForSelector(
                        TraceOverlay.HOST,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(1000)))
                .isInstanceOf(TimeoutError.class);

        // Not vacuous: the bar's own action still works on this same page.
        toolbar.traceId();
        toolbar.click(".pk-toolbar");
        overlay.awaitOpened();
        assertThat(page.isVisible(TraceOverlay.HOST)).isTrue();
    }

    /**
     * /persons runs a real JPA query and dispatches to a real controller method, so the trace's
     * insights (once the fetch ladder picks them up) carry a real query count and a real
     * controller name - no fetch stubbing needed. This also exercises the ladder's happy path
     * end to end: the query spans have not reached the store when the first attempt fires, so
     * this wait only succeeds if the later attempts run and re-render with what they find. The
     * persons lookup also logs its result inside its own span, so the same trace proves the bar
     * reads spans, queries and logs in the overlay's tab order.
     */
    @Test
    void toolbarShowsQueryCountAndControllerNameAfterTraceCompletes() {
        openPersonsPage();
        toolbar.waitUntil("root => { const text = root.querySelector('#pk-metrics').textContent;"
                + " return text.includes('quer') && text.includes('log'); }");

        String metrics = toolbar.text("#pk-metrics");
        assertThat(metrics).contains("1 query");
        assertThat(toolbar.text("#pk-controller")).contains("PersonController.persons");

        Matcher spans = Pattern.compile("\\d[\\d,]* spans?").matcher(metrics);
        Matcher queries = Pattern.compile("1 query").matcher(metrics);
        Matcher logs = Pattern.compile("\\d[\\d,]* logs?").matcher(metrics);
        assertThat(spans.find()).as("a span count: %s", metrics).isTrue();
        assertThat(queries.find()).as("a query count: %s", metrics).isTrue();
        assertThat(logs.find()).as("a log count: %s", metrics).isTrue();
        assertThat(spans.start()).as("spans before queries: %s", metrics).isLessThan(queries.start());
        assertThat(queries.start()).as("queries before logs: %s", metrics).isLessThan(logs.start());
    }

    /**
     * /?error=true logs a real ERROR from inside the request, so the trace's log summary
     * carries a real error count once the toolbar's poll picks it up.
     */
    @Test
    void toolbarShowsErrorLogCountWhenRequestLogsAnError() {
        page.navigate(baseUrl + "/?error=true");
        toolbar.waitUntil("root => root.querySelector('#pk-metrics').textContent.includes('err')");

        assertThat(toolbar.text("#pk-metrics")).contains("1 err");
    }

    /**
     * The dashboard's stored locale groups the bar's own counts and travels with it into
     * the overlay it opens - storage.js's readLocaleSetting() is the one thing both
     * surfaces read, the same way they already share the theme. The route patch mirrors
     * TraceOverlayIT.overlayTabCountsAreGroupedInTheDashboardsLocale's pattern, applied to
     * every one of the toolbar's fetch-ladder attempts rather than a single dashboard fetch.
     */
    @Test
    void toolbarAndItsOverlayGroupCountsInTheDashboardsStoredLocale() {
        page.addInitScript("localStorage.setItem('peekaboot-locale', 'de-DE')");
        patchSpanCountTo(12345);

        openPersonsPage();
        toolbar.waitUntil("root => root.querySelector('#pk-metrics').textContent.includes('span')");

        assertThat(toolbar.text("#pk-metrics")).contains("12.345 spans");

        toolbar.openOverlay();
        overlay.waitFor(".pk-tab[data-tab=\"spans\"] .pk-tab__count");
        assertThat(overlay.text(".pk-tab[data-tab=\"spans\"] .pk-tab__count")).isEqualTo("12.345");
    }

    /**
     * A stale, non-BCP-47 stored tag (an old build's underscore-separated 'en_US') must not
     * blank the bar: storage.js's readLocaleSetting() rejects it before it ever reaches
     * toLocaleString, so the browser's own locale (en-US, pinned by newContextOptions())
     * applies instead - proven by the grouped count still rendering rather than the bar
     * getting stuck on "loading" or throwing.
     */
    @Test
    void toolbarFallsBackToTheBrowserLocaleWhenTheStoredTagIsInvalid() {
        page.addInitScript("localStorage.setItem('peekaboot-locale', 'en_US')");
        patchSpanCountTo(12345);

        openPersonsPage();
        toolbar.waitUntil("root => root.querySelector('#pk-metrics').textContent.includes('span')");

        assertThat(toolbar.text("#pk-metrics")).contains("12,345 spans");
    }

    /**
     * Patches every insights response for the polled trace to carry {@code count} spans -
     * registered before the trace exists, so the ladder's earlier attempts (a real 404
     * until the trace is stored) must pass through untouched rather than fail parsing an
     * error body. Mirrors TraceOverlayIT.overlayTabCountsAreGroupedInTheDashboardsLocale's
     * route-patch pattern.
     */
    private void patchSpanCountTo(int count) {
        page.route("**/api/traces/*/insights", route -> {
            APIResponse response = route.fetch();
            if (!response.ok()) {
                route.fulfill(new Route.FulfillOptions().setResponse(response));
                return;
            }
            ObjectNode trace = (ObjectNode) readJson(response.text());
            ((ObjectNode) trace.get("summary").get("spans")).put("count", count);
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(trace.toString()));
        });
    }

    /**
     * Aborting the specific trace-insights request is a real network failure (Chromium's real
     * net stack refusing the request), not a fabricated response - the bar must still render the
     * pending state instead of leaving "loading" up forever. Pending is deliberately withheld
     * until the last of the four attempts has failed, which lands at 4.75s, so the wait is given
     * room beyond that rather than racing it.
     */
    @Test
    void toolbarShowsPendingWhenTheTraceRequestFails() {
        page.route("**/api/traces/*/insights", route -> route.abort());

        openPersonsPage();

        toolbar.waitUntil("root => root.querySelector('#pk-metrics .pk-toolbar__pending') !== null");

        boolean hasPendingElement =
                (Boolean) toolbar.evaluate("root => !!root.querySelector('#pk-metrics .pk-toolbar__pending')");
        assertThat(hasPendingElement).isTrue();
        assertThat(toolbar.text("#pk-metrics")).contains("?");
    }

    /**
     * Regular pages must not wrap window.fetch at all - only idle mode (Swagger UI) does.
     * window.fetch is captured via addInitScript so it runs before any page script, toolbar.js
     * included.
     */
    @Test
    void toolbarDoesNotWrapFetchInRegularMode() {
        page.addInitScript("window.__pkOriginalFetch = window.fetch;");
        openPersonsPage();

        assertThat(page.evaluate("() => window.fetch === window.__pkOriginalFetch"))
                .isEqualTo(true);
    }

    /**
     * openOverlay()'s dynamic import of trace-detail.js can reject (404, offline, a host
     * page's CSP blocking the module) - toolbar.js runs inside pages Peekaboot does not
     * own, so an unhandled rejection there would surface as *that host's* error on any
     * page wired to Sentry/Datadog/etc. Aborting the module request is a real network
     * failure (Chromium's real net stack refusing it), not a fabricated response, and
     * makes the dynamic import deterministically reject. The click handler must catch it
     * (a console.warn, no pageerror) and the bar must remain usable afterwards.
     *
     * A failed dynamic import of the same URL is cached by the browser's module map, so
     * clicking again with the request still blocked - rather than unroute()-ing and
     * expecting a real reopen - is the reliable way to prove the bar is not stuck: a
     * second click must still reach the handler and log its own warning, not silently
     * do nothing (which a broken listener - e.g. one that removed itself, or got wedged
     * on the first rejection - would).
     */
    @Test
    void openOverlayImportFailureIsCaughtAndLeavesTheBarUsable() {
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        page.route("**/trace-detail/trace-detail.js", route -> route.abort());

        openPersonsPage();
        toolbar.traceId();

        // waitForConsoleMessage blocks until the warning fires (or times out), giving
        // positive proof the rejection was handled rather than merely not-yet-observed.
        page.waitForConsoleMessage(
                new Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg -> msg.type().equals("warning")),
                () -> toolbar.click(".pk-toolbar"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible(TraceOverlay.HOST)).isFalse();

        // Not stuck: a second click (still blocked) must still reach the handler and
        // produce its own warning, rather than the listener having wedged or detached
        // itself after the first rejection.
        page.waitForConsoleMessage(
                new Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg -> msg.type().equals("warning")),
                () -> toolbar.click(".pk-toolbar"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible(TraceOverlay.HOST)).isFalse();
    }

    /**
     * Idle mode (Swagger UI) wraps window.fetch and, on a real fetch to a real endpoint,
     * picks the trace id up from the response's real Server-Timing header - proving the
     * interceptor is wired to the module-local loadTrace rather than a dropped global.
     */
    @Test
    void idleModeInterceptsFetchAndPicksUpTraceIdFromServerTiming() {
        page.addInitScript("window.__pkOriginalFetch = window.fetch;");
        page.navigate(baseUrl + "/swagger-ui/index.html");
        page.waitForSelector("#peekaboot-toolbar-host");

        assertThat(page.evaluate("() => window.fetch === window.__pkOriginalFetch"))
                .isEqualTo(false);

        page.evaluate("() => fetch('/api/person/all')");

        toolbar.traceId();
    }

    /**
     * The bar lives inside pages Peekaboot does not own, and rem resolves against the
     * host document's root font size - the common html{font-size:62.5%} reset would
     * shrink a rem-scaled bar to 7.5px. The :host block in tokens.css pins the type scale
     * in px for the shadow roots, so the bar reads the same on every host.
     */
    @Test
    void toolbarTypeScaleDoesNotFollowTheHostPagesRootFontSize() {
        openPersonsPage();
        toolbar.waitUntil("root => root.querySelector('#pk-status').textContent.trim() !== ''");
        page.addStyleTag(new Page.AddStyleTagOptions().setContent("html { font-size: 62.5%; }"));

        assertThat(toolbar.evaluate("root => getComputedStyle(root.querySelector('.pk-toolbar')).fontSize"))
                .isEqualTo("12px");
        assertThat(toolbar.evaluate("root => getComputedStyle(root.querySelector('#pk-status')).fontSize"))
                .isEqualTo("12px");
    }

    /**
     * On a phone-sized viewport the bar wraps instead of pushing its content past the
     * right edge - a bar that overflows takes the host page's horizontal scroll with it.
     */
    @Test
    void toolbarWrapsInsteadOfOverflowingANarrowViewport() {
        page.setViewportSize(375, 667);
        openPersonsPage();
        toolbar.waitUntil("root => root.querySelector('#pk-metrics').textContent.includes('quer')");

        Boolean everythingFits = (Boolean) toolbar.evaluate("root => {"
                + "const bar = root.querySelector('.pk-toolbar');"
                + "return bar.scrollWidth <= bar.clientWidth"
                + "  && [...bar.querySelectorAll('*')].every(el => el.getBoundingClientRect().right <= 375.5);"
                + "}");
        assertThat(everythingFits)
                .as("no part of the bar reaches past the 375px viewport")
                .isTrue();
    }
}
