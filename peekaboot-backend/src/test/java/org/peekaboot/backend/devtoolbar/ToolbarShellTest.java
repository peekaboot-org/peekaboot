package org.peekaboot.backend.devtoolbar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The bar is rendered on the server rather than built by toolbar.js, so that a
 * reader who has put Spring Security in front of {@code /peekaboot/**} - which refuses
 * toolbar.js along with everything else under that prefix - still gets a bar that says why
 * it is empty and a link that takes them somewhere they can authenticate.
 *
 * <p>That only works if the shell is self-sufficient: the stylesheets it needs travel with
 * it, inlined from the classpath, because a linked sheet is refused by the same gate that
 * refused the script.
 */
class ToolbarShellTest {

    private static final String DATA_JSON = "{\"method\":\"GET\",\"path\":\"/persons\",\"basePath\":\"/peekaboot\"}";

    private final ToolbarShell shell = new ToolbarShell();

    @Test
    void rendersTheBarMarkupIntoADeclarativeShadowRoot() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains("id=\"peekaboot-toolbar-host\"");
        assertThat(html).contains("<template shadowrootmode=\"open\">");
        assertThat(html).contains("class=\"pk-toolbar\"");
        assertThat(html).contains("id=\"pk-metrics\"");
        assertThat(html).contains("id=\"pk-status\"");
    }

    @Test
    void carriesTheDataBlobAndTheEnhancingModule() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains(DATA_JSON);
        assertThat(html).contains("<script src=\"/peekaboot/ui/toolbar/toolbar.js\" type=\"module\">");
    }

    /**
     * The whole point of inlining. tokens.css carries the custom properties every rule in
     * toolbar.css resolves against, so a gate that refuses one refuses the bar's entire
     * appearance unless both travel with the markup.
     */
    @Test
    void inlinesTheStylesheetsThatAnAuthorizationGateWouldOtherwiseRefuse() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains("--pk-bg:");
        assertThat(html).contains(".pk-toolbar__open");
        assertThat(html).contains(".pk-toolbar__auth");
    }

    /**
     * A relative {@code url()} in a stylesheet resolves against the stylesheet. Inlined into
     * the page it would resolve against the page instead, so every one is rewritten to the
     * path it had while it was still a file.
     */
    @Test
    void rewritesStylesheetRelativeUrlsToTheirServedPaths() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains("url('/peekaboot/ui/assets/logo-mark.png')");
        assertThat(html).doesNotContain("url('../assets/");
    }

    /**
     * Each sheet is inlined <em>and</em> linked. A host page whose CSP omits
     * {@code style-src 'unsafe-inline'} drops the inline copy - the toolbar survives such a
     * page today only because toolbar.js builds its {@code <link>} elements through the
     * CSSOM, which CSP does not govern, and losing that would regress every reader who has
     * no authorization gate at all. The link keeps them working; the inline copy keeps the
     * gated case working. Same bytes from the same file either way.
     */
    @Test
    void inlinesAndAlsoLinksEachSheetSoAStrictCspKeepsOne() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/peekaboot/ui/assets/tokens.css\">");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/peekaboot/ui/assets/base.css\">");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/peekaboot/ui/assets/components.css\">");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/peekaboot/ui/toolbar/toolbar.css\">");
    }

    /**
     * Positioning lives in a {@code :host} rule rather than an inline style set by
     * toolbar.js, so it travels with whichever copy of the sheet a given page is
     * allowed to use. An inline {@code style} attribute would be dropped by the same strict
     * CSP that drops the inline sheet, leaving the bar unpositioned in the page flow.
     */
    @Test
    void takesItsPositioningFromTheStylesheetNotAnInlineStyleAttribute() {
        String html = shell.render(DATA_JSON);

        assertThat(html).doesNotContain("style=\"position");
        assertThat(html).contains("position: fixed");
    }

    /**
     * The notice has to be a real link rather than something the bar's click handler opens:
     * in the case it exists for, no handler was ever bound because no script ever loaded.
     */
    @Test
    void theNoticeIsARealLinkToTheDashboard() {
        String html = shell.render(DATA_JSON);

        assertThat(html).contains("class=\"pk-toolbar__auth\"");
        assertThat(html).contains("href=\"/peekaboot/\"");
    }

    /**
     * A link is interactive content and the bar's open control is a real {@code <button>},
     * so nesting the two would be invalid HTML - browsers reparent it, which would put the
     * notice outside the bar entirely.
     */
    @Test
    void theNoticeSitsOutsideTheOpenButton() {
        String html = shell.render(DATA_JSON);

        int buttonEnd = html.indexOf("</button>");
        int noticeStart = html.indexOf("class=\"pk-toolbar__auth\"");

        assertThat(buttonEnd).isPositive();
        assertThat(noticeStart).isGreaterThan(buttonEnd);
    }
}
