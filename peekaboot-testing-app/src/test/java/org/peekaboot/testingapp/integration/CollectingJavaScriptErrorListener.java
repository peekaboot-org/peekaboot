package org.peekaboot.testingapp.integration;

import org.htmlunit.ScriptException;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.javascript.JavaScriptErrorListener;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replaces HtmlUnit's default ERROR-logging listener. Known engine
 * incompatibilities (allow-list) are swallowed; anything else fails the
 * test via assertNoUnexpectedErrors() so real regressions in our JS can't
 * hide in ignored log noise.
 */
final class CollectingJavaScriptErrorListener implements JavaScriptErrorListener {

    private static final List<String> ALLOWED_MESSAGE_PARTS = List.of(
            // HtmlUnit's JS engine doesn't implement Element.attachShadow.
            "Cannot find function attachShadow",
            // HtmlUnit's JS engine doesn't parse the object-literal syntax used by toolbar.js.
            "Unexpected token in object literal"
    );

    private final List<String> unexpected = new ArrayList<>();

    static CollectingJavaScriptErrorListener installOn(WebClient client) {
        CollectingJavaScriptErrorListener listener = new CollectingJavaScriptErrorListener();
        client.setJavaScriptErrorListener(listener);
        return listener;
    }

    void assertNoUnexpectedErrors() {
        assertThat(unexpected).as("unexpected HtmlUnit script errors").isEmpty();
    }

    private void record(String message) {
        if (ALLOWED_MESSAGE_PARTS.stream().noneMatch(message::contains)) {
            unexpected.add(message);
        }
    }

    @Override
    public void scriptException(HtmlPage page, ScriptException e) {
        record(e.getMessage());
    }

    @Override
    public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {
        record("Timeout during JavaScript execution after " + executionTime
                + "ms; allowed only " + allowedTime + "ms");
    }

    @Override
    public void malformedScriptURL(HtmlPage page, String url, MalformedURLException e) {
        record("Unable to build URL for script src tag [" + url + "]: " + e.getMessage());
    }

    @Override
    public void loadScriptError(HtmlPage page, URL scriptUrl, Exception e) {
        record("Error loading JavaScript from [" + scriptUrl + "]: " + e.getMessage());
    }

    @Override
    public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {
        record(message);
    }
}
