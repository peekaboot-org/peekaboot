package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Response;
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

    /**
     * The per-run disclosures work with no script at all; this proves the global control on
     * top of them - opening every run on the first click, and, since {@code open} is
     * recomputed from {@code aria-pressed} on every click, closing every run again on the
     * second.
     */
    @Test
    void theRevealControlOpensEveryHiddenRun() {
        page.navigate(baseUrl + "/boom");
        assertThat(page.locator("details.pk-error__hidden").first().isVisible()).isTrue();

        page.click(".pk-error__reveal");

        assertThat(page.locator("details.pk-error__hidden[open]").count())
                .isEqualTo(page.locator("details.pk-error__hidden").count());
        assertThat(page.getAttribute(".pk-error__reveal", "aria-pressed")).isEqualTo("true");
        assertThat(page.textContent(".pk-error__reveal")).isEqualTo("Hide framework frames");

        page.click(".pk-error__reveal");

        assertThat(page.locator("details.pk-error__hidden[open]").count()).isEqualTo(0);
        assertThat(page.getAttribute(".pk-error__reveal", "aria-pressed")).isEqualTo("false");
        assertThat(page.textContent(".pk-error__reveal")).isEqualTo("Show full stack trace");
    }

    /** A host with script-src 'self' drops the inline copy; the linked one still arms the control. */
    @Test
    void theRevealControlSurvivesAScriptSrcSelfPolicy() {
        serveWithCsp("**/boom", "script-src 'self'");

        Response navigation = page.navigate(baseUrl + "/boom");

        assertThat(navigation.headers())
                .as("the policy reached the document under test")
                .containsEntry("content-security-policy", "script-src 'self'");
        page.click(".pk-error__reveal");

        assertThat(page.locator("details.pk-error__hidden[open]").count()).isGreaterThan(0);
    }
}
