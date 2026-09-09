package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ToolbarShell links every inlined stylesheet as well, because the two channels answer to
 * different halves of a Content-Security-Policy: {@code style-src}'s {@code 'unsafe-inline'}
 * keyword governs the inline copy in the shadow root, while a same-origin
 * {@code <link rel="stylesheet">} is governed by the directive's source list instead, so
 * dropping {@code 'unsafe-inline'} leaves the link untouched. This proves that split against
 * a real browser enforcing a real policy - applied to the real /persons response the same way
 * ThemeResolutionIT applies one to the dashboard - rather than only against the markup
 * ToolbarShellTest inspects.
 */
class CspToolbarIT extends PlaywrightTestBase {

    @Test
    void theBarStaysStyledWhenThePolicyDropsTheInlineStylesheet() {
        captureBrowserSignals();
        page.addInitScript("window.__cspViolations = [];"
                + "document.addEventListener('securitypolicyviolation',"
                + " e => window.__cspViolations.push(e.violatedDirective));");
        serveWithCsp("**/persons", "style-src 'self'");

        Response navigation = page.navigate(baseUrl + "/persons");
        page.waitForSelector("#peekaboot-toolbar-host[data-pk-ready='true']");

        assertThat(navigation.headers())
                .as("the policy reached the document under test")
                .containsEntry("content-security-policy", "style-src 'self'");
        @SuppressWarnings("unchecked")
        List<String> violations = (List<String>) page.evaluate("() => window.__cspViolations");
        assertThat(violations)
                .as("the inline stylesheet was actually dropped, not merely allowed to pass")
                .anyMatch(directive -> directive.startsWith("style-src"));
        assertThat(cssVar("#peekaboot-toolbar-host", "position")).isEqualTo("fixed");
    }
}
