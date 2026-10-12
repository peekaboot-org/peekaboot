package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Escape belongs to the overlay only when the overlay is what the reader is in: it closes
 * the overlay wherever focus sits inside it, or when focus has fallen out of it onto the
 * page body, and then goes no further. An Escape something else handles first, or one aimed
 * at an element outside the overlay that is still live, is left alone.
 *
 * <p>Every press here is synchronous: Playwright returns once the key event has been
 * dispatched, and closing is synchronous too, so "still open" is read straight after the
 * press rather than waited for.
 */
class TraceOverlayEscapeIT extends PlaywrightTestBase {

    private static final String PAGE_ESCAPES = "() => window.pageEscapes";

    @BeforeEach
    void openTheOverlayOverTheDashboard() {
        openPersonsPage();
        String traceId = toolbar.traceId();
        awaitTrace(traceId, ROOT_SPAN_EXPORTED);

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor(".pk-tab");
        page.evaluate("() => { window.pageEscapes = 0; document.addEventListener('keydown', event => {"
                + " if (event.key === 'Escape') window.pageEscapes += 1; }); }");
    }

    @Test
    void escapeThatClosesTheOverlayGoesNoFurther() {
        page.keyboard().press("Escape");

        overlay.awaitClosed();
        assertThat(page.evaluate(PAGE_ESCAPES)).isEqualTo(0);
    }

    @Test
    void escapeClosesTheOverlayAfterAClickOnItsPlainText() {
        page.locator(".pk-overlay__title-path").click();

        page.keyboard().press("Escape");

        overlay.awaitClosed();
    }

    /** A focused control the overlay re-renders away leaves focus on the body, behind the overlay. */
    @Test
    void escapeClosesTheOverlayWhenFocusHasFallenToThePageBody() {
        overlay.evaluate("root => root.activeElement.blur()");
        assertThat(page.evaluate("() => document.activeElement === document.body"))
                .isEqualTo(true);

        page.keyboard().press("Escape");

        overlay.awaitClosed();
        assertThat(page.evaluate(PAGE_ESCAPES)).isEqualTo(0);
    }

    /** Stands in for a popover inside the overlay that closes itself on Escape. */
    @Test
    void escapeAControlInsideTheOverlayHandledDoesNotAlsoClose() {
        overlay.evaluate("root => { const tab = root.querySelector('.pk-tab');"
                + " tab.addEventListener('keydown', event => event.preventDefault()); tab.focus(); }");

        page.keyboard().press("Escape");

        assertThat(page.querySelector(TraceOverlay.HOST)).isNotNull();
    }

    /** Stands in for a page widget that is live above the overlay, such as a top-layer popover. */
    @Test
    void escapeAimedAtALiveElementOutsideTheOverlayIsLeftToIt() {
        page.evaluate("() => { const outside = document.createElement('button');"
                + " outside.textContent = 'outside'; document.body.append(outside); outside.focus(); }");

        page.keyboard().press("Escape");

        assertThat(page.querySelector(TraceOverlay.HOST)).isNotNull();
        assertThat(page.evaluate(PAGE_ESCAPES)).isEqualTo(1);
    }
}
