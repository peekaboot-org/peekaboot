package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
              + ".shadowRoot.querySelector('.pk-toolbar')).getPropertyValue(prop).trim()", property);
    }

    private String shadowText(String id) {
        return (String) page.evaluate(
                "id => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.getElementById(id).textContent", id);
    }

    @Test
    void toolbarFollowsTheStoredLightPreference() {
        setStoredTheme("light");
        openPersonsPage();
        page.waitForSelector("#peekaboot-toolbar-host");

        assertThat(shadowVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void toolbarFollowsTheStoredDarkPreference() {
        setStoredTheme("dark");
        openPersonsPage();
        page.waitForSelector("#peekaboot-toolbar-host");

        assertThat(shadowVar("--pk-bg")).isEqualTo("#0d1117");
    }

    @Test
    void toolbarShowsMethodPathAndStatusForTheRequest() {
        openPersonsPage();
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-status').textContent.trim() !== ''");

        String status = (String) page.evaluate(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-status').textContent");
        String path = (String) page.evaluate(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-path').textContent");

        assertThat(status).isEqualTo("200");
        assertThat(path).isEqualTo("/persons");
    }

    @Test
    void clickingTheBarOpensTheTraceOverlay() {
        openPersonsPage();
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
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
     * The bar is a role=button/tabindex=0 control, not a plain div: Enter must open the
     * overlay exactly like a mouse click, without requiring a click event at all.
     */
    @Test
    void theBarIsKeyboardOperableAndOpensTheOverlayOnEnter() {
        openPersonsPage();
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");

        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                    + ".shadowRoot.querySelector('.pk-toolbar').focus()");
        page.keyboard().press("Enter");

        page.waitForSelector("#peekaboot-trace-overlay");
        assertThat(page.isVisible("#peekaboot-trace-overlay")).isTrue();
    }

    /**
     * The dashboard link inside the bar must stay independently focusable/activatable and
     * must not also trigger the bar's own click-to-open-overlay action (event.stopPropagation).
     */
    @Test
    void theDashboardLinkDoesNotTriggerTheBarsOwnAction() {
        openPersonsPage();

        boolean linkIsFocusable = (Boolean) page.evaluate(
                "() => { const a = document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('.pk-toolbar a'); a.focus();"
              + " return document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.activeElement === a; }");
        assertThat(linkIsFocusable).isTrue();

        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                    + ".shadowRoot.querySelector('.pk-toolbar a').click()");

        assertThat(page.isVisible("#peekaboot-trace-overlay")).isFalse();
    }

    /**
     * /persons runs a real JPA query and dispatches to a real controller method, so the trace's
     * insights (once the retry/backoff poll picks them up) carry a real query count and a real
     * controller name - no fetch stubbing needed.
     */
    @Test
    void toolbarShowsQueryCountAndControllerNameAfterTraceCompletes() {
        openPersonsPage();
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-metrics').textContent.includes('quer')",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

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
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertThat(shadowText("pk-metrics")).contains("1 err");
    }

    /**
     * Aborting the specific trace-insights request is a real network failure (Chromium's real
     * net stack refusing the request), not a fabricated response - loadTrace's fetchTrace().catch
     * must still render the pending state instead of leaving "loading" up forever.
     */
    @Test
    void toolbarShowsPendingWhenTheTraceRequestFails() {
        page.route("**/api/traces/*/insights", route -> route.abort());

        openPersonsPage();

        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-metrics').textContent.includes('?')",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));
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

        assertThat(page.evaluate("() => window.fetch === window.__pkOriginalFetch")).isEqualTo(true);
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

        assertThat(page.evaluate("() => window.fetch === window.__pkOriginalFetch")).isEqualTo(false);

        page.evaluate("() => fetch('/api/person/all')");

        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));
    }
}
