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
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.peekaboot.testingapp.TestingApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class PlaywrightTestBase {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightTestBase.class);

    /**
     * A JS predicate on the trace JSON: the request's own root span has reached the store.
     * Only a SERVER-kind root classifies HTTP_REQUEST, the root is the last span of a request
     * to end, and the exporter hands spans over in the order they ended, so every span that
     * ended before it is there too. Logs need no wait of their own: they are captured
     * synchronously during the request.
     */
    protected static final String ROOT_SPAN_EXPORTED = "trace => trace.rootActionType === 'HTTP_REQUEST'";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int API_TIMEOUT_MS = 15_000;
    private static final int API_POLL_INTERVAL_MS = 100;
    private static final int LOG_CAPTURE_ATTEMPTS = 5;

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
    private volatile boolean tearingDown;

    /**
     * Prints what {@link #captureBrowserSignals()} collected, for a failing test only - a green
     * run has nothing to explain. A TestWatcher runs after {@link #closePage()}, which is why
     * the signals are buffered rather than printed as they arrive.
     */
    @RegisterExtension
    final TestWatcher browserSignalReport = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            if (!browserSignals.isEmpty()) {
                System.out.println("[browser] " + String.join("\n[browser] ", browserSignals));
            }
        }
    };

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
     * page mute in test output - so every browser-side signal is collected and printed when
     * the test fails, the only way to see what headless Chromium actually did on a CI runner.
     */
    protected void captureBrowserSignals() {
        page.onConsoleMessage(msg -> recordBrowserSignal("console." + msg.type() + ": " + msg.text()));
        page.onPageError(error -> recordBrowserSignal("pageerror: " + error));
        page.onRequestFailed(
                request -> recordBrowserSignal("requestfailed: " + request.url() + " -> " + request.failure()));
        page.onResponse(response -> {
            if (response.status() >= 400) {
                recordBrowserSignal("http" + response.status() + ": " + response.url());
            }
        });
    }

    /**
     * What the teardown itself provokes - the navigation to {@code about:blank} aborting the
     * Insights tab's EventSource, say - is not the test's doing and is not recorded.
     */
    private void recordBrowserSignal(String signal) {
        if (!tearingDown) {
            browserSignals.add(signal);
        }
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
        tearingDown = true;
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
     * Loads the page that logs one ERROR line, and returns its trace id once that trace
     * actually carries the log.
     *
     * <p>Reloads until it does. Every application context that starts in this JVM has Spring
     * Boot re-initialise Logback, which detaches peekaboot's capture appender until
     * {@code LogbackCaptureReinstaller} puts it back; a request served in that window is
     * traced with no logs against it. Test classes run concurrently here, so contexts start
     * throughout a suite run. The toolbar publishes its trace id from the server-rendered
     * blob before it has fetched anything, and the overlay fetches its trace once and never
     * refreshes - so a caller that navigates and opens the Logs tab on that signal alone
     * renders a tab that stays empty, with nothing left to bring the rows in. Once the root
     * span is stored the verdict is final, since logs are captured synchronously.
     */
    protected String openPageThatLogsAnError() {
        for (int attempt = 0; attempt < LOG_CAPTURE_ATTEMPTS; attempt++) {
            page.navigate(baseUrl + "/?error=true");
            String traceId = toolbar.traceId();
            if (!awaitTrace(traceId, ROOT_SPAN_EXPORTED).path("logs").isEmpty()) {
                return traceId;
            }
            log.info(
                    "trace {} of /?error=true carries no log (capture appender detached by a context boot), reloading",
                    traceId);
        }
        throw new AssertionError("no /?error=true request produced a trace carrying its own ERROR log");
    }

    /**
     * Polls the trace's own insights endpoint, the one the overlay and the toolbar read, until
     * {@code jsPredicate} (a JS function of the trace JSON) holds, and returns the trace as
     * served at that moment. A 404 counts as "not yet": the endpoint answers nothing until the
     * first span is exported. The predicate is the assertion's precondition, so a test asserts
     * on what it waited for rather than on whatever had arrived.
     */
    protected JsonNode awaitTrace(String traceId, String jsPredicate) {
        return awaitJson(
                "/peekaboot/api/traces/" + traceId + "/insights",
                "trace => (" + jsPredicate + ")(trace) ? trace : null",
                "trace " + traceId + " never satisfied " + jsPredicate);
    }

    /**
     * Polls the listing endpoint with {@code query} until a listed trace satisfies
     * {@code jsPredicate}, and returns that trace's id. The listing is shared with every class
     * running against this application, so the predicate names the caller's own trace (its
     * {@code rootOperation}, say) rather than accepting whichever is listed first.
     */
    protected String awaitListedTrace(String query, String jsPredicate) {
        return awaitJson(
                        "/peekaboot/api/traces/insights?" + query,
                        "listing => (listing.traces || []).find(" + jsPredicate + ")",
                        "no listed trace for '" + query + "' satisfied " + jsPredicate)
                .path("traceId")
                .asString();
    }

    /**
     * Polls a JSON endpoint from the page until {@code jsSelect} (a JS function of the parsed
     * body) returns something truthy, and hands that value back. Runs as one in-page loop
     * rather than a Java-side one, so a single fetch serves both the check and the returned
     * value. The page has to be on the application's origin for the fetch, so a page that is
     * not (a fresh context, say) is pointed at the blank fixture first.
     */
    protected JsonNode awaitJson(String path, String jsSelect, String failure) {
        return awaitJson(path, jsSelect, failure, API_TIMEOUT_MS);
    }

    protected static JsonNode readJson(String json) {
        return JSON.readTree(json);
    }

    protected JsonNode awaitJson(String path, String jsSelect, String failure, int timeoutMs) {
        if (!page.url().startsWith(baseUrl)) {
            openBlankFixture();
        }
        String json = (String) page.evaluate(
                "async ([url, failure, timeoutMs, pollIntervalMs]) => {"
                        + " const select = (" + jsSelect + ");"
                        + " const deadline = Date.now() + timeoutMs;"
                        + " let last = null;"
                        + " while (Date.now() < deadline) {"
                        + "  const response = await fetch(url);"
                        + "  if (response.ok) {"
                        + "   const body = await response.json();"
                        + "   const value = select(body);"
                        + "   if (value) return JSON.stringify(value);"
                        + "   last = body;"
                        + "  }"
                        + "  await new Promise(resolve => setTimeout(resolve, pollIntervalMs));"
                        + " }"
                        + " throw new Error(failure + ' within ' + timeoutMs + 'ms; last body: ' + JSON.stringify(last));"
                        + "}",
                List.of(path, failure, timeoutMs, API_POLL_INTERVAL_MS));
        return readJson(json);
    }

    /**
     * The blank same-origin fixture page, for module imports and API polls that need no
     * surface. Its status is asserted because a 404 whitelabel page hosts an {@code import()}
     * or a {@code fetch()} just as well as the fixture does.
     */
    protected void openBlankFixture() {
        String url = baseUrl + "/peekaboot/ui/pk-blank.html";
        if (!page.url().equals(url)) {
            int status = page.navigate(url).status();
            if (status != 200) {
                throw new AssertionError("GET " + url + " answered " + status);
            }
        }
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
