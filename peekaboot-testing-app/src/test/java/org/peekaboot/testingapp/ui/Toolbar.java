package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;

/** The dev toolbar (toolbar.js) the sample app injects into its server-rendered pages. */
final class Toolbar extends ShadowHost {

    Toolbar(Page page) {
        super(page, "peekaboot-toolbar-host");
    }

    /**
     * Waits until the bar tracks the page's own trace and returns the bare id: #pk-trace's
     * text is "traceId<hex>" plus the copy icon, the id itself lives on the copy control.
     */
    String traceId() {
        waitUntil("root => root.querySelector('#pk-trace').textContent.trim() !== '-'");
        return (String) evaluate("root => root.querySelector('#pk-trace .pk-copy').dataset.pkCopy");
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
