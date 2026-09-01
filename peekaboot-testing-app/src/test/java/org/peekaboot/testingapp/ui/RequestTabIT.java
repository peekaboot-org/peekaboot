package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises trace-detail/tabs/request.js directly, with synthetic trace data, rather
 * than through the real toolbar -> overlay -> captured-request flow. A natural browser
 * navigation never sends a header shaped to be masked, so there would be no way to
 * distinguish a "this was masked" highlight keyed on the backend's actual six-star
 * "******" mask from one keyed on a stale eight-star literal without fabricating a
 * masked value. The same directness lets a status code of any family be
 * rendered on demand, which a live request against the testing app cannot offer.
 */
class RequestTabIT extends PlaywrightTestBase {

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
        // The backend masks with six stars, so an eight-star value reaching the frontend
        // would be a backend bug, not a masked value - it must NOT be highlighted. A
        // highlight keyed on '********' would also silently never fire for a real masked
        // header.
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
     * Both header tables are sections of one scrolling page rather than sub-tabs: a strip
     * that never holds more than three entries would cost three clicks to see one request.
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
