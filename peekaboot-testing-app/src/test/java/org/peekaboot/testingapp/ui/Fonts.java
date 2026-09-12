package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;

/**
 * The webfont gate every geometry assertion sits behind. Peekaboot serves Geist from its own
 * jar, so a measurement taken while the face is still loading measures the system fallback
 * and the assertion still passes - the failure mode this exists to remove. Awaited at the
 * three points a surface first becomes measurable rather than in each test: the dashboard
 * after its render, a host page after the bar has enhanced itself, and the overlay once its
 * loading placeholder is gone.
 */
final class Fonts {

    /** Long enough for a cold jar-served face, short enough to fail inside the suite's own runtime. */
    private static final double LOAD_TIMEOUT_MS = 10_000;

    private Fonts() {}

    /**
     * Waits until no face is loading. Not {@code document.fonts.ready}: that promise re-arms
     * every time another face starts loading, and awaiting it through {@code page.evaluate}
     * carries no timeout at all, so a woff2 that never arrives hangs the build with no output.
     */
    static void awaitReady(Page page) {
        page.waitForFunction(
                "() => document.fonts.status === 'loaded'",
                null,
                new Page.WaitForFunctionOptions().setTimeout(LOAD_TIMEOUT_MS));
    }
}
