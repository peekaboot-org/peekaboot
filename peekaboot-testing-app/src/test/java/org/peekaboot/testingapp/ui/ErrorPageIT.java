package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * /boom is served by the error dispatch, which renders Peekaboot's page and carries the bar
 * for the request that failed - the two things a developer looks at when their own page
 * throws.
 */
class ErrorPageIT extends PlaywrightTestBase {

    @Test
    void theErrorPageNamesTheExceptionAndItsStackTrace() {
        page.navigate(baseUrl + "/boom");
        page.waitForSelector(".pk-error");

        assertThat(page.textContent(".pk-error"))
                .contains("500")
                .contains("java.lang.IllegalStateException")
                .contains("order reconciliation gateway is unreachable");
        assertThat(page.locator(".pk-error__frame--app").count())
                .as("the sample app's own frames are marked, or the trace is a wall of framework lines")
                .isGreaterThan(0);
    }

    @Test
    void theBarOnTheErrorPageShowsTheFailedRequest() {
        page.navigate(baseUrl + "/boom");
        toolbar.waitUntil("root => root.querySelector('#pk-status').textContent.trim() !== ''");

        assertThat(toolbar.text("#pk-status")).isEqualTo("500");
        assertThat(toolbar.text("#pk-path")).isEqualTo("/boom");
    }

    /** The bar's trace opens the trace the failing request produced, and its Request tab agrees on the status. */
    @Test
    void theTraceBehindTheBarReportsTheFailedRequest() {
        page.navigate(baseUrl + "/boom");

        toolbar.openOverlay();
        overlay.openTab("request");
        overlay.waitFor(".pk-table--kv");

        assertThat((String)
                        overlay.evaluate("root => root.querySelector('#pk-tab-content .pk-badge').textContent.trim()"))
                .contains("500");
    }
}
