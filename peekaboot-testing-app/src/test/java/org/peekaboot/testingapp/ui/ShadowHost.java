package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;

/**
 * A Peekaboot surface rendered into a shadow root on the current page. Every script here
 * receives that shadow root as {@code root}, so a test reads {@code root.querySelector(...)}
 * instead of repeating the host lookup; the waits are null-safe because the host itself
 * may not exist yet (the overlay is created by the code under test).
 */
abstract class ShadowHost {

    private static final double WAIT_TIMEOUT_MS = 15_000;

    protected final Page page;
    private final String shadowRootLookup;

    ShadowHost(Page page, String hostId) {
        this.page = page;
        this.shadowRootLookup = "document.getElementById('" + hostId + "')?.shadowRoot";
    }

    /** Evaluates {@code fn(root, arg)} - {@code fn} being a JS function source - and returns its result. */
    Object evaluate(String fn, Object arg) {
        return page.evaluate("arg => (" + fn + ")(" + shadowRootLookup + ", arg)", arg);
    }

    Object evaluate(String fn) {
        return evaluate(fn, null);
    }

    /** Waits until {@code predicate(root, arg)} is truthy; a still-missing host counts as false. */
    void waitUntil(String predicate, Object arg) {
        page.waitForFunction(
                "arg => { const root = " + shadowRootLookup + "; return root ? (" + predicate
                        + ")(root, arg) : false; }",
                arg,
                new Page.WaitForFunctionOptions().setTimeout(WAIT_TIMEOUT_MS));
    }

    void waitUntil(String predicate) {
        waitUntil(predicate, null);
    }

    void waitFor(String selector) {
        waitUntil("(root, selector) => !!root.querySelector(selector)", selector);
    }

    void waitForGone(String selector) {
        waitUntil("(root, selector) => !root.querySelector(selector)", selector);
    }

    String text(String selector) {
        return (String) evaluate("(root, selector) => root.querySelector(selector).textContent", selector);
    }

    void click(String selector) {
        evaluate("(root, selector) => root.querySelector(selector).click()", selector);
    }

    /** The resolved value of a CSS custom property on the first match of {@code selector}. */
    String cssVar(String selector, String property) {
        return (String) evaluate(
                "(root, args) => getComputedStyle(root.querySelector(args[0])).getPropertyValue(args[1]).trim()",
                java.util.List.of(selector, property));
    }
}
