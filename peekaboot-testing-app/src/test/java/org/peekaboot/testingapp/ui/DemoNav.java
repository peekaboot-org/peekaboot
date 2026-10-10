package org.peekaboot.testingapp.ui;

/** How the browser tests read the demo nav: its links and forms, and the target each one points at. */
final class DemoNav {

    /** A link's href, or a form's action. */
    static final String TARGET = "entry.getAttribute(entry.tagName === 'A' ? 'href' : 'action')";

    private DemoNav() {}

    /** A page.evaluate function mapping every nav link and form through the given expression on `entry`. */
    static String mapEntries(String expression) {
        return "() => [...document.querySelectorAll('#demo-nav a, #demo-nav form')].map(entry => " + expression + ")";
    }
}
