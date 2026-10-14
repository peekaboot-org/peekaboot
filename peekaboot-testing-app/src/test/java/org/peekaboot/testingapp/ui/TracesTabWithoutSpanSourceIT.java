package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.BooleanNode;

/**
 * An application without the OpenTelemetry SDK still has a trace store, so the Traces tab
 * shows, but nothing can ever fill it. The tab says why and what to add instead of listing
 * nothing forever.
 *
 * <p>The sample app has the SDK, so this context leaves out {@code OtelTracingAutoConfiguration},
 * whose span exporter is the one thing {@code tracingSpansPossible} is read from. An inlined
 * exclusion replaces application-test.yml's list rather than merging with it, so the servlet
 * security exclusions are repeated; anything added there belongs here as well.
 */
@TestPropertySource(
        properties = "spring.autoconfigure.exclude="
                + "org.peekaboot.autoconfigure.OtelTracingAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet"
                + ".ManagementWebSecurityAutoConfiguration")
class TracesTabWithoutSpanSourceIT extends PlaywrightTestBase {

    private static final String MARKER_REQUEST = "/peekaboot/api/features?after-traces-tab";

    @Test
    void theTracesTabExplainsWhatToAddInsteadOfListing() {
        assertThat(awaitJson(
                                contextPath() + "/peekaboot/api/features",
                                "features => features",
                                "/api/features answered nothing")
                        .path("tracingSpansPossible"))
                .as("this context wires no span exporter")
                .isEqualTo(BooleanNode.FALSE);
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
}
