package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.impl.TargetClosedError;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
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

    /**
     * One browser per worker thread, launched on first use. Playwright's Java objects are
     * confined to the thread that created them, and test classes run concurrently (one
     * worker per class; methods stay on the class's thread), so each worker owns a whole
     * Playwright instance. Isolation between tests comes from the per-test context, not
     * the browser, and JUnit has no per-JVM {@code @AfterAll}, so the matching closes
     * hang off JVM shutdown instead.
     */
    private static final List<Playwright> STARTED_PLAYWRIGHTS = new CopyOnWriteArrayList<>();

    private static final ThreadLocal<Browser> WORKER_BROWSER = ThreadLocal.withInitial(() -> {
        Playwright playwright = Playwright.create();
        STARTED_PLAYWRIGHTS.add(playwright);
        return playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(PlaywrightTestBase::closeBrowsers, "playwright-close"));
    }

    @LocalServerPort
    protected int port;

    protected Page page;
    protected Toolbar toolbar;
    protected TraceOverlay overlay;
    protected String baseUrl;

    private final List<String> browserSignals = new CopyOnWriteArrayList<>();

    protected static Browser browser() {
        return WORKER_BROWSER.get();
    }

    private static void closeBrowsers() {
        for (Playwright playwright : STARTED_PLAYWRIGHTS) {
            try {
                playwright.close();
            } catch (RuntimeException e) {
                // The JVM is exiting and the driver process dies with it; a close that trips
                // over that shutdown must not spray a stack trace into otherwise-green output.
                log.warn(
                        "swallowed {} closing Playwright at JVM shutdown: {}",
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }

    @BeforeEach
    void openPage() {
        baseUrl = "http://localhost:" + port;
        page = browserContextPage();
        toolbar = new Toolbar(page);
        overlay = new TraceOverlay(page);
    }

    /**
     * Opt-in for tests whose subject fails invisibly - a swallowed script error leaves the
     * page mute in test output - so every browser-side signal is collected and printed at
     * teardown, the only way to see what headless Chromium actually did on a CI runner.
     */
    protected void captureBrowserSignals() {
        page.onConsoleMessage(msg -> browserSignals.add("console." + msg.type() + ": " + msg.text()));
        page.onPageError(error -> browserSignals.add("pageerror: " + error));
        page.onRequestFailed(
                request -> browserSignals.add("requestfailed: " + request.url() + " -> " + request.failure()));
        page.onResponse(response -> {
            if (response.status() >= 400) {
                browserSignals.add("http" + response.status() + ": " + response.url());
            }
        });
    }

    /** Overridable so a subclass can fix the viewport without changing every test's context. */
    protected Page browserContextPage() {
        return browser().newContext(newContextOptions()).newPage();
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
        if (!browserSignals.isEmpty()) {
            System.out.println("[browser] " + String.join("\n[browser] ", browserSignals));
        }
        if (page != null) {
            try {
                // Ends everything the page still has in flight - the toolbar's fetch ladder
                // (it polls /api/traces/{id}/insights for up to 4.75s after load), the
                // Insights tab's EventSource, half-finished fetches - deterministically and
                // in milliseconds, where waiting for the network to go idle cost up to its
                // full 2s timeout on any page with a poller.
                page.navigate("about:blank");
            } catch (PlaywrightException e) {
                // teardown must never fail a passing test
                log.warn(
                        "swallowed {} navigating away during teardown: {}",
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
            try {
                page.context().close();
            } catch (TargetClosedError e) {
                // Kept although about:blank should have stopped every request producer: a
                // scheduled poll firing while close() is in flight makes Playwright sync
                // route-interception patterns against a target that is already gone. The
                // context is closing either way - that is this call's whole goal - so a race
                // in Playwright's own bookkeeping on the way there is not a real failure.
                log.warn("swallowed TargetClosedError closing browser context during teardown: {}", e.getMessage());
            }
        }
    }

    protected void openDashboard() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        page.waitForSelector("#overview-tab.active");
        // #loading is the app's own readiness signal: hidden only after fetchData() -> renderData()
        page.waitForSelector("#loading", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        // ...but #loading also hides on the failure path, so require positive proof of a render
        page.waitForSelector("#build-info > *, #error:not(.hidden)");
        if (page.isVisible("#error")) {
            throw new IllegalStateException("dashboard failed to load: " + page.textContent("#error .message"));
        }
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
