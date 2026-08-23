package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises trace-detail/tabs/request.js directly, with synthetic trace data, rather
 * than through the real toolbar -> overlay -> captured-request flow. A natural browser
 * navigation never sends a header shaped to be masked, so there would be no way to
 * distinguish the fix (M1: the "this was masked" highlight compared against the old
 * eight-star literal, which the backend's engine had already stopped emitting in
 * favour of Spring's own six-star "******") from the regression it fixes without
 * fabricating a masked value.
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

    private void openRequestHeadersSubtab() {
        page.click("#pk-request-test-container .pk-tab[data-tab=\"request-headers\"]");
    }

    @Test
    void requestHeadersTabHighlightsAValueMatchingTheCurrentMaskLiteral() {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"authorization": "******", "accept": "application/json"}},
                    "response": {"status": 200, "headers": {}}
                }}
                """);
        openRequestHeadersSubtab();

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked").count()).isEqualTo(1);
        assertThat(page.locator("#pk-request-test-container td.pk-request-masked").textContent()).isEqualTo("******");
    }

    @Test
    void requestHeadersTabDoesNotHighlightTheStaleEightStarLiteral() {
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
        openRequestHeadersSubtab();

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked").count()).isZero();
    }

    @Test
    void requestHeadersTabDoesNotHighlightOrdinaryValues() {
        renderWithTrace("""
                {"durationMs": 12, "httpExchange": {
                    "request": {"method": "GET", "path": "/api/users",
                        "headers": {"accept": "application/json", "host": "example.com"}},
                    "response": {"status": 200, "headers": {}}
                }}
                """);
        openRequestHeadersSubtab();

        assertThat(page.locator("#pk-request-test-container td.pk-request-masked").count()).isZero();
    }
}
