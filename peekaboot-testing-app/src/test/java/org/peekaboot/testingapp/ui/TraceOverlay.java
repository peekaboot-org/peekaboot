package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;

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
        return this;
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
