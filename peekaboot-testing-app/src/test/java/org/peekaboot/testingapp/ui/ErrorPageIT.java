package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.microsoft.playwright.Response;
import java.util.List;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;

/**
 * /boom is served by the error dispatch, which renders Peekaboot's page and carries the bar
 * for the request that failed - the two things a developer looks at when their own page
 * throws.
 */
class ErrorPageIT extends PlaywrightTestBase {

    private static final ThrowingConsumer<Double> TRACE_IS_SINGLE_SPACED =
            step -> assertThat(step).isCloseTo(1.0, within(0.05));

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

    /**
     * Each visible line of the trace - a frame or a hidden run's summary - sits one line
     * height below the one before it, and the last one ends the box, folded or revealed.
     */
    @Test
    void theStackTraceIsSingleSpaced() {
        page.navigate(baseUrl + "/boom");

        assertThat(lineStepsOfTheTrace()).as("folded").hasSizeGreaterThan(2).allSatisfy(TRACE_IS_SINGLE_SPACED);

        page.click(".pk-error__reveal");

        assertThat(lineStepsOfTheTrace()).as("revealed").hasSizeGreaterThan(2).allSatisfy(TRACE_IS_SINGLE_SPACED);
    }

    @Test
    void copyingTheStackTraceGivesOneLinePerFrame() {
        page.navigate(baseUrl + "/boom");
        page.click(".pk-error__reveal");

        String copied = (String) page.evaluate("() => {"
                + " const selection = getSelection();"
                + " selection.selectAllChildren(document.querySelector('.pk-error__frames'));"
                + " return selection.toString(); }");

        assertThat(copied).doesNotContain("\n\n");
        assertThat(copied.lines())
                .hasSize(page.locator(".pk-error__frame").count()
                        + page.locator(".pk-error__hidden-summary").count());
    }

    /**
     * The distance from each visible line of the trace to the next, and from the last one to
     * the bottom of the box's content, in line heights.
     */
    private List<Double> lineStepsOfTheTrace() {
        List<?> steps = (List<?>) page.evaluate("() => {"
                + " const frames = document.querySelector('.pk-error__frames');"
                + " const style = getComputedStyle(frames);"
                + " const lineHeight = parseFloat(style.lineHeight);"
                + " const tops = [...frames.querySelectorAll('.pk-error__frame, .pk-error__hidden-summary')]"
                + "     .filter(line => line.checkVisibility())"
                + "     .map(line => line.getBoundingClientRect().top);"
                + " const contentBottom = frames.getBoundingClientRect().top + frames.clientTop"
                + "     + frames.clientHeight - parseFloat(style.paddingBottom);"
                + " return [...tops.slice(1), contentBottom].map((next, i) => (next - tops[i]) / lineHeight); }");
        return steps.stream().map(step -> ((Number) step).doubleValue()).toList();
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
