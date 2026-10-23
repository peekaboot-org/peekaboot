package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.time.Duration;

/** The trace-detail overlay (trace-detail.js), opened from the toolbar or a dashboard deep link. */
final class TraceOverlay extends ShadowHost {

    static final String HOST = "#peekaboot-trace-overlay";

    TraceOverlay(Page page) {
        super(page, "peekaboot-trace-overlay");
    }

    /**
     * Waits until the loading placeholder is gone. The host exists as soon as
     * openTraceDetail() creates it, well before fetchAndRender() replaces the placeholder
     * with either render()'s content or the error state; waiting for the placeholder's
     * absence rather than a success-only element serves both paths without racing either.
     */
    TraceOverlay awaitLoaded() {
        page.waitForSelector(HOST);
        waitForGone(".pk-overlay__loading");
        Fonts.awaitReady(page);
        return this;
    }

    /**
     * Waits for {@code selector} and then the webfont, the two things a geometry assertion
     * needs. A test that navigates straight to a deep link never passes through
     * {@link #awaitLoaded()} and would otherwise measure the system fallback.
     */
    void awaitMeasurable(String selector) {
        waitFor(selector);
        Fonts.awaitReady(page);
    }

    /** Waits for the host alone: openTraceDetail() creates it before any fetch, so this proves an open was started, nothing more. */
    void awaitOpened() {
        page.waitForSelector(HOST);
    }

    /**
     * Asserts that no overlay host attaches within {@code window}, {@code because} naming why.
     * Opening is async (a dynamic import and a fetch), so an absence read straight away
     * would pass even if something were opening it.
     */
    void assertNotOpenedWithin(Duration window, String because) {
        assertThatThrownBy(() -> page.waitForSelector(
                        HOST,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout((double) window.toMillis())))
                .as(because)
                .isInstanceOf(TimeoutError.class);
    }

    /** Waits until the overlay open is the one for {@code traceId}, which trace-detail.js stamps on the host. */
    void awaitTrace(String traceId) {
        page.waitForSelector(HOST + "[data-trace-id='" + traceId + "']");
    }

    /** Switches to a tab once render() has built the strip (trace fetch and stylesheets both resolved). */
    void openTab(String tab) {
        String selector = ".pk-tab[data-tab=\"" + tab + "\"]";
        waitFor(selector);
        click(selector);
    }

    /** Opens the Logs tab and waits for its rows, so the trace has to carry at least one log. */
    void openLogsTab() {
        openTab("logs");
        waitFor(".pk-log");
    }

    String selectedTab() {
        return (String) evaluate("root => root.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
    }

    void awaitClosed() {
        page.waitForCondition(() -> page.querySelector(HOST) == null);
    }

    String cssVar(String property) {
        return cssVar(".pk-overlay", property);
    }
}
