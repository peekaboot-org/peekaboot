package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.Map;
import java.util.regex.Pattern;

/** The dashboard's tab strip and the panels it switches between (dashboard/main.js). */
final class Dashboard {

    /**
     * The selector each tab renders once its own data has arrived. {@code #<id>-tab.active}
     * only proves the panel is showing, not that its render() has populated it, so a tab is
     * opened against this instead. The Traces tab counts as rendered with an empty list too:
     * the list is shared with every class, so which trace a test needs is its own business
     * (see {@link #awaitListedTrace}).
     */
    private static final Map<String, String> TAB_READY_SELECTOR = Map.ofEntries(
            Map.entry("overview", "#memory-info .pk-meter__fill"),
            Map.entry("insights", "#insights-panels .pk-insight-panel"),
            Map.entry("lifecycle", "#lifecycle-runs .pk-table--card tbody tr"),
            Map.entry("traces", "#traces-list .pk-trace-item, #no-traces:not(.hidden)"),
            Map.entry("meters", "#meters-list .pk-group"),
            Map.entry("environment", "#property-sources .pk-group__header"),
            Map.entry("flyway", "#flyway-timeline .pk-table tbody tr"),
            Map.entry("loggers", "#loggers-list .pk-group"),
            Map.entry("config", "#config-groups .pk-group__header"),
            Map.entry("scheduled-tasks", "#scheduled-tasks-groups .pk-group"));

    private final Page page;

    Dashboard(Page page) {
        this.page = page;
    }

    static String tabButton(String tabId) {
        return ".pk-tab[data-tab='" + tabId + "']";
    }

    /** Clicks the tab's button and waits for the tab to render its data. */
    void openTab(String tabId) {
        String ready = TAB_READY_SELECTOR.get(tabId);
        if (ready == null) {
            throw new IllegalArgumentException("no ready selector known for tab '" + tabId + "'");
        }
        page.click(tabButton(tabId));
        page.waitForSelector(ready);
    }

    void openTracesTab() {
        openTab("traces");
    }

    String selectedTab() {
        return (String)
                page.evaluate("() => document.querySelector('#main-tabs .pk-tab[aria-selected=\"true\"]').dataset.tab");
    }

    /** The listed row of one trace; the list is shared with every class, so a test opens its own. */
    static String traceItem(String traceId) {
        return "#traces-list .pk-trace-item[data-trace-id='" + traceId + "']";
    }

    void awaitListedTrace(String traceId) {
        page.waitForSelector(traceItem(traceId));
    }

    /**
     * Opens the listed trace's overlay through its own row, the way a reader does, and
     * waits for its tab strip: render() is where trace-detail.js takes focus and starts
     * listening for Escape, so a keypress before that is lost.
     */
    void openListedTrace(String traceId) {
        page.click(traceItem(traceId) + " .pk-trace-item__open");
        TraceOverlay overlay = new TraceOverlay(page);
        overlay.awaitTrace(traceId);
        overlay.waitFor(".pk-tab");
    }

    /** The value of the {@code .pk-kv} row under {@code container} whose key reads exactly {@code key}. */
    String kvValue(String container, String key) {
        return kvValueCell(container, key).textContent();
    }

    /** Waits for that row's value to read exactly {@code expected}; the row may sit in a collapsed group. */
    void awaitKvValue(String container, String key, String expected) {
        kvValueCell(container, key)
                .filter(new Locator.FilterOptions().setHasText(exactly(expected)))
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
    }

    /**
     * Located through text filters rather than {@code :text-is()}: that pseudo-class reads an
     * element's immediate text nodes only, and a key the tab's filter has highlighted keeps
     * its text inside a {@code <mark>} child.
     */
    private Locator kvValueCell(String container, String key) {
        Locator keyCell = page.locator(".pk-kv__key").filter(new Locator.FilterOptions().setHasText(exactly(key)));
        return page.locator(container + " .pk-kv")
                .filter(new Locator.FilterOptions().setHas(keyCell))
                .locator(".pk-kv__value");
    }

    /** A whole-text match; the pattern reaches the browser as a JS RegExp, so it is escaped by hand. */
    private static Pattern exactly(String text) {
        return Pattern.compile("^" + text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0") + "$");
    }
}
