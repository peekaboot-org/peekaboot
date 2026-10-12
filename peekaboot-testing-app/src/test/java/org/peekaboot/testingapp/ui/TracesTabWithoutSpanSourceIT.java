package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Route;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * An application without the OpenTelemetry SDK still has a trace store, so the Traces tab
 * shows, but nothing can ever fill it. The tab says why and what to add instead of listing
 * nothing forever.
 *
 * <p>The sample app has the SDK, so the real /api/features response is served with
 * {@code tracingSpansPossible} flipped; that the backend reports false without the SDK is
 * PeekabootAutoConfigurationTest's to prove.
 */
class TracesTabWithoutSpanSourceIT extends PlaywrightTestBase {

    private static final String MARKER_REQUEST = "/peekaboot/api/features?after-traces-tab";

    @Test
    void theTracesTabExplainsWhatToAddInsteadOfListing() {
        page.route("**/peekaboot/api/features", route -> {
            APIResponse features = route.fetch();
            route.fulfill(new Route.FulfillOptions()
                    .setResponse(features)
                    .setBody(features.text()
                            .replace("\"tracingSpansPossible\":true", "\"tracingSpansPossible\":false")));
        });
        List<String> requests = new CopyOnWriteArrayList<>();
        page.onRequest(request -> requests.add(request.url()));

        openDashboard();
        page.click(Dashboard.tabButton("traces"));
        page.waitForSelector("#traces-unavailable:not(.hidden)");

        assertThat(page.textContent("#traces-unavailable"))
                .contains("OpenTelemetry SDK")
                .contains("org.springframework.boot:spring-boot-starter-opentelemetry");
        assertThat(page.isVisible("#traces-bucket"))
                .as("no buckets to choose from")
                .isFalse();
        assertThat(page.isVisible("#traces-filter")).as("no types to filter by").isFalse();
        assertThat(page.isVisible("#no-traces"))
                .as("the explanation replaces the plain empty state")
                .isFalse();

        // Requests are reported in the order the page issued them, so once this one is in, a
        // listing request the tab issued when it was opened would be in too.
        page.evaluate("url => fetch(url)", contextPath() + MARKER_REQUEST);
        page.waitForCondition(() -> requests.stream().anyMatch(url -> url.endsWith(MARKER_REQUEST)));
        assertThat(requests)
                .as("a listing that can never hold anything is not fetched")
                .noneMatch(url -> url.contains("/peekaboot/api/traces"));
    }

    /** The positive control for the flip: with spans possible the tab lists as usual. */
    @Test
    void theTracesTabListsAsUsualWhenSpansArePossible() {
        openDashboard();
        dashboard.openTracesTab();

        assertThat(page.isVisible("#traces-bucket")).isTrue();
        assertThat(page.isVisible("#traces-unavailable")).isFalse();
    }
}
