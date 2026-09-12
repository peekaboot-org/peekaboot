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

    private Fonts() {}

    static void awaitReady(Page page) {
        page.evaluate("() => document.fonts.ready.then(() => null)");
    }
}
