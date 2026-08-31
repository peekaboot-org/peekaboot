package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises trace-detail/tabs/request.js directly, with synthetic trace data, rather
 * than through the real toolbar -> overlay -> captured-request flow. A natural browser
 * navigation never sends a header shaped to be masked, so there would be no way to
 * distinguish the fix (M1: the "this was masked" highlight compared against the old
 * eight-star literal, which the backend's engine had already stopped emitting in
 * favour of Spring's own six-star "******") from the regression it fixes without
 * fabricating a masked value. The same directness lets a status code of any family be
 * rendered on demand, which a live request against the testing app cannot offer.
 */
class RequestTabTest extends PlaywrightTestBase {

    private void renderWithTrace(String traceJson) {
        if (!page.url().equals(baseUrl + "/peekaboot/ui/pk-blank.html")) {
            page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");
        }
        page.evaluate(
                "async (traceJson) => {"
                        + " const trace = JSON.parse(traceJson);"
                        + " const m = await import('/peekaboot/ui/trace-detail/tabs/request.js');"
                        + " const container = document.createElement('div');"
                        + " container.id = 'pk-request-test-container';"
                        + " document.body.appendChild(container);"
                        + " m.render(container, trace);"
                        + "}",
                traceJson);
    }

    private void renderWithStatus(int status) {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users", "headers": {}},
                    "response": {"status": %d, "headers": {}}
                }}
                """.formatted(status));
    }

    @Test
    void requestHeadersHighlightAValueMatchingTheCurrentMaskLiteral() {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"authorization": "******", "accept": "application/json"}},
                    "response": {"status": 200, "headers": {}}
                }}
                """);

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked")
                        .count())
                .isEqualTo(1);
        assertThat(page.locator("#pk-request-test-container td.pk-request-masked")
                        .textContent())
                .isEqualTo("******");
    }

    @Test
    void requestHeadersDoNotHighlightTheStaleEightStarLiteral() {
        // Pins the actual regression: the old frontend copy compared against
        // '********' (eight stars), which the backend no longer emits at all, so the
        // highlight silently never fired for a real masked header. An eight-star
        // value reaching the frontend would be a backend bug, not a masked value -
        // it must NOT be highlighted either.
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"x-legacy": "********"}},
                    "response": {"status": 200, "headers": {}}
                }}
                """);

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked")
                        .count())
                .isZero();
    }

    @Test
    void requestHeadersDoNotHighlightOrdinaryValues() {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"accept": "application/json", "host": "example.com"}},
                    "response": {"status": 200, "headers": {}}
                }}
                """);

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked")
                        .count())
                .isZero();
    }

    /**
     * Both header tables used to sit behind their own sub-tabs, so seeing a request
     * meant three clicks through a strip that never held more than three entries. They
     * are now sections of one scrolling page.
     */
    @Test
    void everySectionRendersOnOnePageWithoutNavigation() {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"accept": "application/json"}},
                    "response": {"status": 200, "headers": {"content-type": "application/json"}}
                }}
                """);

        String text = page.locator("#pk-request-test-container").textContent();
        assertThat(text).contains("Request Headers", "accept", "application/json");
        assertThat(text).contains("Response Headers", "content-type");
    }

    @Test
    void theRequestPageHasNoSubTabStrip() {
        renderWithStatus(200);

        assertThat(page.locator("#pk-request-test-container .pk-tabs").count()).isZero();
    }

    /**
     * Both header sections stay on the page even with nothing to show, so "no headers
     * were captured" stays distinguishable from "this build stopped rendering them".
     */
    @Test
    void bothHeaderSectionsSurviveATraceWithNoHeaders() {
        renderWithStatus(200);

        String text = page.locator("#pk-request-test-container").textContent();
        assertThat(text).contains("Request Headers", "Response Headers");
        assertThat(page.locator("#pk-request-test-container")
                        .getByText("No headers captured")
                        .count())
                .isEqualTo(2);
    }

    @Test
    void theStatusRowSpellsOutTheReasonPhrase() {
        renderWithStatus(404);

        assertThat(page.locator("#pk-request-test-container .pk-badge").textContent())
                .isEqualTo("404 Not Found");
    }

    @Test
    void theStatusRowColoursAClientErrorWithTheSoftDangerTier() {
        renderWithStatus(404);

        assertThat(page.locator("#pk-request-test-container .pk-badge").getAttribute("class"))
                .contains("pk-badge--error-soft");
    }

    @Test
    void theStatusRowColoursAServerErrorWithTheFullDangerTier() {
        renderWithStatus(500);

        assertThat(page.locator("#pk-request-test-container .pk-badge").getAttribute("class"))
                .contains("pk-badge--error");
        assertThat(page.locator("#pk-request-test-container .pk-badge").getAttribute("class"))
                .doesNotContain("pk-badge--error-soft");
    }
}
