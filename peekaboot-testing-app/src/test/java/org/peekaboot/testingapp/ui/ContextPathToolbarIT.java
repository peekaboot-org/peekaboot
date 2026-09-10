package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.TimeoutError;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the sample app behind a {@code server.servlet.context-path} - in a context of its
 * own, so every other class keeps the root-mounted app - and proves Peekaboot follows the
 * prefix on both sides. The toolbar the filter injects loads its script, its stylesheets and
 * its trace data from under {@code /app/peekaboot}; the dashboard, served from there too,
 * reaches its API and opens its overlay without configuration; and the dashboard's own
 * requests are still recognised as Peekaboot's despite the context path in front of them,
 * so they are neither given a toolbar nor captured as traces.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/app")
class ContextPathToolbarIT extends PlaywrightTestBase {

    private static final String CONTEXT_PATH = "/app";

    private static final String NETWORK_CHANGED = "net::ERR_NETWORK_CHANGED";

    /** The bar tracks the page's own trace; the same wait Toolbar.traceId() makes, here with a timeout of the test's choosing. */
    private static final String TRACE_ID_SHOWN = "root => root.querySelector('#pk-trace').textContent.trim() !== '-'";

    @Override
    protected String contextPath() {
        return CONTEXT_PATH;
    }

    @Autowired
    private TraceStore traceStore;

    /** Every Peekaboot resource the page asked for and did not get - a 404 here is the bug this class exists for. */
    private final List<String> failedPeekabootRequests = new CopyOnWriteArrayList<>();

    private final List<String> pageErrors = new CopyOnWriteArrayList<>();

    @BeforeEach
    void watchPeekabootRequests() {
        page.onResponse(response -> {
            if (response.status() >= 400 && response.url().contains("/peekaboot/")) {
                failedPeekabootRequests.add(response.status() + " " + response.url());
            }
        });
        page.onRequestFailed(request -> {
            // Not ERR_NETWORK_CHANGED: that is the browser dropping everything in flight
            // because the host's network configuration changed, and says nothing about the
            // context path (see docs/TESTING.md, "Requests the browser loses").
            if (request.url().contains("/peekaboot/") && !NETWORK_CHANGED.equals(request.failure())) {
                failedPeekabootRequests.add("failed " + request.url() + ": " + request.failure());
            }
        });
        page.onPageError(pageErrors::add);
    }

    @Test
    void theToolbarLoadsItsScriptStylesAndTraceDataFromBehindTheContextPath() {
        openPersonsPageBehindTheContextPath();
        awaitQueryCountOnTheBar();

        String scriptSrc = (String) page.evaluate(
                "() => document.querySelector('script[src$=\"/ui/toolbar/toolbar.js\"]').getAttribute('src')");
        assertThat(scriptSrc).isEqualTo("/app/peekaboot/ui/toolbar/toolbar.js");
        assertThat(failedPeekabootRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    /** The overlay's module and stylesheets are addressed from the base path the data blob carries. */
    @Test
    void theOverlayOpenedFromTheToolbarLoadsFromBehindTheContextPath() {
        openPersonsPageBehindTheContextPath();
        awaitQueryCountOnTheBar();

        toolbar.openOverlay();
        overlay.waitFor(".pk-tab");

        assertThat(failedPeekabootRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    /**
     * The dashboard is served from under the context path and must find its API there without
     * being told; and its own calls must not be mistaken for application requests, which is
     * what would happen if the exclusion matched on the raw, prefixed request URI.
     */
    @Test
    void theDashboardLoadsBehindTheContextPathAndIsNotTreatedAsAHostPage() {
        // A real application request first, so the "nothing of Peekaboot's was captured"
        // assertion below is made against a store that demonstrably captures.
        openPersonsPageBehindTheContextPath();

        openDashboard();

        assertThat(page.locator("#peekaboot-toolbar-host").count()).isZero();
        assertThat(failedPeekabootRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
        List<String> capturedPaths = capturedRequestPaths();
        assertThat(capturedPaths).contains(CONTEXT_PATH + "/persons");
        assertThat(capturedPaths).noneMatch(path -> path.startsWith(CONTEXT_PATH + "/peekaboot/"));
    }

    /** The dashboard opens the overlay without naming a base path; the derived default has to be the prefixed one. */
    @Test
    void theOverlayOpenedFromTheDashboardLoadsFromBehindTheContextPath() {
        openPersonsPageBehindTheContextPath();
        awaitQueryCountOnTheBar();
        String traceId = (String) page.evaluate(
                "() => JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent).traceId");

        // the hash lands on the Traces tab, so its list - not the Overview's build info - is
        // the proof that the dashboard rendered
        openDashboard("#traces/" + traceId, "#traces-list .pk-trace-item");
        overlay.waitFor(".pk-tab");

        assertThat(failedPeekabootRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    /**
     * Idle mode ignores Swagger UI's own calls by path prefix; behind a context path those
     * calls carry the prefix too. A bounded absence (expecting the timeout) proves the
     * api-docs call was ignored, and the application call right after proves the negative
     * was not vacuous.
     */
    @Test
    void idleModeIgnoresSwaggersOwnCallsBehindTheContextPath() {
        page.navigate(baseUrl + CONTEXT_PATH + "/swagger-ui/index.html");
        page.waitForSelector("#peekaboot-toolbar-host[data-pk-ready='true']");

        // evaluate() awaits the promise: the response, Server-Timing header and all, is in
        // before the absence check starts, and nothing is left in flight for teardown to cut off
        page.evaluate("() => fetch('" + CONTEXT_PATH + "/v3/api-docs').then(r => r.text())");
        assertThatThrownBy(() -> toolbar.waitUntil(TRACE_ID_SHOWN, null, 1000)).isInstanceOf(TimeoutError.class);

        page.evaluate("() => fetch('" + CONTEXT_PATH + "/api/person/all').then(r => r.text())");
        toolbar.waitUntil(TRACE_ID_SHOWN, null, 10000);

        assertThat(pageErrors).isEmpty();
    }

    private void openPersonsPageBehindTheContextPath() {
        openPersonsPage();
        // toolbar.js sets data-pk-ready itself, so its presence is the proof that the module
        // was fetched from the prefixed URL the shell wrote
        page.waitForSelector("#peekaboot-toolbar-host[data-pk-ready='true']");
    }

    /** A query count can only come from /app/peekaboot/api/traces/{id}/insights, and only once the trace is stored. */
    private void awaitQueryCountOnTheBar() {
        toolbar.waitUntil("root => root.querySelector('#pk-metrics').textContent.includes('quer')");
    }

    private List<String> capturedRequestPaths() {
        return traceStore.getTraces(TraceBucket.ALL, Integer.MAX_VALUE).stream()
                .map(TraceDataBundle::request)
                .filter(Objects::nonNull)
                .map(RequestCompletedEvent::path)
                .toList();
    }
}
