package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import java.util.List;

/** The dev toolbar (toolbar.js) the sample app injects into its server-rendered pages. */
final class Toolbar extends ShadowHost {

    private static final String TRACE_TRACKED = "root => root.querySelector('#pk-trace').textContent.trim() !== '-'";

    Toolbar(Page page) {
        super(page, "peekaboot-toolbar-host");
    }

    /**
     * Waits until the bar tracks the page's own trace and returns the bare id: #pk-trace's
     * text is "traceId<hex>" plus the copy icon, the id itself lives on the copy control.
     */
    String traceId() {
        waitUntil(TRACE_TRACKED);
        return (String) evaluate("root => root.querySelector('#pk-trace .pk-copy').dataset.pkCopy");
    }

    /** Waits up to {@code timeoutMs} for the bar to track a trace; a test proving it never does passes a short one. */
    void awaitTraceTracked(double timeoutMs) {
        waitUntil(TRACE_TRACKED, null, timeoutMs);
    }

    /**
     * Waits until the stat line reads every one of {@code fragments} ("quer", "log"...). The
     * counts arrive with the trace's insights, so a fragment proves the bar has read them.
     */
    void awaitMetrics(String... fragments) {
        waitUntil(
                "(root, fragments) => { const text = root.querySelector('#pk-metrics').textContent;"
                        + " return fragments.every(fragment => text.includes(fragment)); }",
                List.of(fragments));
    }

    /** Waits until the bar shows the response status. */
    void awaitStatus() {
        waitUntil("root => root.querySelector('#pk-status').textContent.trim() !== ''");
    }

    /**
     * Clicks the bar - its "open trace details" action - once it tracks a trace, and hands
     * the overlay back once its loading placeholder is gone, whichever way the fetch went.
     */
    TraceOverlay openOverlay() {
        traceId();
        click(".pk-toolbar");
        return new TraceOverlay(page).awaitLoaded();
    }

    String cssVar(String property) {
        return cssVar(".pk-toolbar", property);
    }
}
