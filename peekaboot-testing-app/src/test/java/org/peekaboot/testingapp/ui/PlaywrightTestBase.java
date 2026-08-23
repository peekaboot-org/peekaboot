package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.impl.TargetClosedError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.peekaboot.testingapp.TestingApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class PlaywrightTestBase {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightTestBase.class);

    private static Playwright playwright;
    protected static Browser browser;

    @LocalServerPort
    protected int port;

    protected Page page;
    protected String baseUrl;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        try {
            if (browser != null) {
                browser.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    @BeforeEach
    void openPage() {
        baseUrl = "http://localhost:" + port;
        page = browserContextPage();
    }

    /** Overridable so a subclass can fix the viewport without changing every test's context. */
    protected Page browserContextPage() {
        return browser.newContext(newContextOptions()).newPage();
    }

    /**
     * Base options every context needs; overriders of {@link #browserContextPage()} should
     * chain onto this. Pins the browser locale: on a POSIX-locale host (CI runners, LANG=C)
     * headless Chromium reports navigator.language as the invalid BCP-47 tag "en-US@posix",
     * which blows up any Intl constructor - uPlot's module-scope
     * Intl.NumberFormat(navigator.language) then kills the whole chart library.
     */
    protected static Browser.NewContextOptions newContextOptions() {
        return new Browser.NewContextOptions().setLocale("en-US");
    }

    @AfterEach
    void closePage() {
        if (page != null) {
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(2000));
            } catch (TimeoutError e) {
                // best-effort drain of in-flight requests; teardown must never fail a passing test
                log.warn("swallowed TimeoutError waiting for network idle during teardown: {}", e.getMessage());
            }
            try {
                page.context().close();
            } catch (TargetClosedError e) {
                // The collapsed toolbar's own fetch ladder (toolbar.js) keeps polling
                // /api/traces/{id}/insights for up to 4.75s after page load, regardless of
                // whether the test that opened the page is still running. A test that routes
                // that endpoint (page.route(...)) and never unroutes it leaves the interceptor
                // registered through teardown, so a scheduled poll can still fire while this
                // close() is in flight; Playwright then tries to sync interception patterns
                // against a target that is already gone. The context is closing either way -
                // that is this call's whole goal - so a race in Playwright's own internal
                // bookkeeping on the way there is not a real teardown failure.
                log.warn("swallowed TargetClosedError closing browser context during teardown: {}", e.getMessage());
            }
        }
    }

    protected void openDashboard() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        page.waitForSelector("#dashboard-tab.active");
        // #loading is the app's own readiness signal: hidden only after fetchData() -> renderData()
        page.waitForSelector("#loading", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        // ...but #loading also hides on the failure path, so require positive proof of a render
        page.waitForSelector("#build-info > *, #error:not(.hidden)");
        if (page.isVisible("#error")) {
            throw new IllegalStateException("dashboard failed to load: " + page.textContent("#error .message"));
        }
    }

    /**
     * Closes whatever the page is holding open - the Insights tab's EventSource above all.
     * A live SSE stream keeps the teardown's NETWORKIDLE drain from ever settling, so a
     * test that opens one pays the drain's full timeout unless it navigates away first.
     */
    protected void closeLiveStreams() {
        page.navigate("about:blank");
    }

    protected void openPersonsPage() {
        page.navigate(baseUrl + "/persons");
        page.waitForSelector("#peekaboot-toolbar-host");
    }

    /**
     * Resolved value of a CSS custom property on the first match of {@code selector}.
     * Throws if the property does not resolve to a value, since an empty string is
     * indistinguishable from "both sides of a comparison are missing the token".
     */
    protected String cssVar(String selector, String property) {
        String value = (String) page.locator(selector)
                .first()
                .evaluate("(el, prop) => getComputedStyle(el).getPropertyValue(prop).trim()", property);
        if (value.isEmpty()) {
            throw new AssertionError("CSS property '" + property + "' does not resolve on '" + selector + "'");
        }
        return value;
    }

    /**
     * Seeds the shared theme preference before any Peekaboot script runs.
     * Note: {@code addInitScript} re-runs on every navigation of this page, not just the
     * first — a test that toggles the theme and then reloads will see the seeded value
     * reapplied, not the toggled one.
     */
    protected void setStoredTheme(String theme) {
        page.addInitScript("localStorage.setItem('peekaboot-theme', '" + theme + "');");
    }
}
