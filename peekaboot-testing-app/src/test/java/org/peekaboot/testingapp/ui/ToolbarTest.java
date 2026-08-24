package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real toolbar.js served by the running app in a real browser. Supersedes the
 * old HtmlUnit-based ToolbarScriptTest, which could not parse toolbar.js once it became an ES
 * module. Coverage that used to come from stubbed fetch/setTimeout now comes from real requests
 * (a real DB-backed /persons request for query counts and controller name, a real logged error
 * for log counts, a real Server-Timing header for idle mode) rather than mocked responses, per
 * the project's no-mocking-in-e2e-tests policy. Aborting a single, specific network request to
 * simulate a real fetch failure is not the same as mocking a fake response, so it is used for
 * the "pending" state test.
 */
class ToolbarTest extends PlaywrightTestBase {

    private String shadowVar(String property) {
        return (String) page.evaluate(
                "prop => getComputedStyle(document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('.pk-toolbar')).getPropertyValue(prop).trim()",
                property);
    }

    private String shadowText(String id) {
        return (String) page.evaluate(
                "id => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.getElementById(id).textContent",
                id);
    }

    /**
     * Headless Chromium's own default is prefers-color-scheme: light, so a naive
     * "storage wins" test in the light direction would pass even if the stored preference
     * were ignored entirely. Forcing the OS preference to the opposite of what's stored
     * makes each test fail if resolveTheme() ever stops preferring localStorage.
     */
    private void emulateOppositeOsPreference(ColorScheme osPreference) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(osPreference));
    }

    @Test
    void toolbarFollowsTheStoredLightPreference() {
        setStoredTheme("light");
        emulateOppositeOsPreference(ColorScheme.DARK);
        openPersonsPage();
        page.waitForSelector("#peekaboot-toolbar-host");

        assertThat(shadowVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void toolbarFollowsTheStoredDarkPreference() {
        setStoredTheme("dark");
        emulateOppositeOsPreference(ColorScheme.LIGHT);
        openPersonsPage();
        page.waitForSelector("#peekaboot-toolbar-host");

        assertThat(shadowVar("--pk-bg")).isEqualTo("#0d1117");
    }

    @Test
    void toolbarShowsMethodPathAndStatusForTheRequest() {
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-status').textContent.trim() !== ''");

        String status = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-status').textContent");
        String path = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-path').textContent");

        assertThat(status).isEqualTo("200");
        assertThat(path).isEqualTo("/persons");
    }

    @Test
    void clickingTheBarOpensTheTraceOverlay() {
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");

        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");

        page.waitForSelector("#peekaboot-trace-overlay");
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isTrue();
    }

    @Test
    void toolbarDoesNotLeakGlobals() {
        openPersonsPage();

        assertThat(page.evaluate("() => typeof window.__peekaboot")).isEqualTo("undefined");
    }

    /**
     * The "open trace details" control is a real &lt;button&gt;: Enter must open the overlay
     * exactly like a mouse click, without any custom keydown handling (native button
     * activation dispatches a real click event that bubbles to the bar's listener).
     */
    @Test
    void theBarIsKeyboardOperableAndOpensTheOverlayOnEnter() {
        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");

        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");

        page.waitForSelector("#peekaboot-trace-overlay");
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isTrue();
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

        boolean linkIsFocusable =
                (Boolean) page.evaluate("() => { const a = document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('.pk-toolbar a'); a.focus();"
                        + " return document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.activeElement === a; }");
        assertThat(linkIsFocusable).isTrue();

        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar a').click()");

        assertThatThrownBy(() -> page.waitForSelector(
                        "#peekaboot-trace-overlay",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(1000)))
                .isInstanceOf(TimeoutError.class);

        // Not vacuous: the bar's own action still works on this same page.
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isTrue();
    }

    /**
     * /persons runs a real JPA query and dispatches to a real controller method, so the trace's
     * insights (once the fetch ladder picks them up) carry a real query count and a real
     * controller name - no fetch stubbing needed. This also exercises the ladder's happy path
     * end to end: the query spans have not reached the store when the first attempt fires, so
     * this wait only succeeds if the later attempts run and re-render with what they find.
     */
    @Test
    void toolbarShowsQueryCountAndControllerNameAfterTraceCompletes() {
        openPersonsPage();
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-metrics').textContent.includes('quer')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        assertThat(shadowText("pk-metrics")).contains("queries");
        assertThat(shadowText("pk-controller")).contains("PersonController.persons");
    }

    /**
     * /?error=true logs a real ERROR from inside the request, so the trace's log summary
     * carries a real error count once the toolbar's poll picks it up.
     */
    @Test
    void toolbarShowsErrorLogCountWhenRequestLogsAnError() {
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-metrics').textContent.includes('err')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        assertThat(shadowText("pk-metrics")).contains("1 err");
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

        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-metrics .pk-toolbar__pending') !== null",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));

        boolean hasPendingElement = (Boolean) page.evaluate("() => !!document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-metrics .pk-toolbar__pending')");
        assertThat(hasPendingElement).isTrue();
        assertThat(shadowText("pk-metrics")).contains("?");
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
        java.util.List<String> pageErrors = new java.util.ArrayList<>();
        page.onPageError(pageErrors::add);
        page.route("**/trace-detail/trace-detail.js", route -> route.abort());

        openPersonsPage();
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");

        // waitForConsoleMessage blocks until the warning fires (or times out), giving
        // positive proof the rejection was handled rather than merely not-yet-observed.
        page.waitForConsoleMessage(
                new Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg -> msg.type().equals("warning")),
                () -> page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('.pk-toolbar').click()"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isFalse();

        // Not stuck: a second click (still blocked) must still reach the handler and
        // produce its own warning, rather than the listener having wedged or detached
        // itself after the first rejection.
        page.waitForConsoleMessage(
                new Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg -> msg.type().equals("warning")),
                () -> page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('.pk-toolbar').click()"));

        assertThat(pageErrors).isEmpty();
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isFalse();
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

        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
    }
}
