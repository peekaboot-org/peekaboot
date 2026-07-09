package net.osslabz.peekaboot.frontend;

import org.htmlunit.MockWebConnection;
import org.htmlunit.ScriptResult;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the real peekaboot-utils.js in a browser engine and verifies
 * that escapeHtml output is safe in both element and attribute context.
 */
class PeekabootUtilsEscapeHtmlTest {

    private WebClient webClient;
    private HtmlPage page;

    @BeforeEach
    void setUp() throws IOException {
        webClient = new WebClient();
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse("<html><head></head><body></body></html>");
        webClient.setWebConnection(connection);
        page = webClient.getPage("http://localhost/test.html");

        String utilsJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/shared/peekaboot-utils.js"));
        page.executeJavaScript(utilsJs);
    }

    @AfterEach
    void tearDown() {
        webClient.close();
    }

    private String escapeHtml(String jsArgumentLiteral) {
        ScriptResult result = page.executeJavaScript("PeekabootUtils.escapeHtml(" + jsArgumentLiteral + ")");
        return (String) result.getJavaScriptResult();
    }

    @Test
    void escapesElementContextCharacters() {
        assertThat(escapeHtml("'<script>alert(1)</scr' + 'ipt> & more'"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more");
    }

    @Test
    void escapesDoubleQuotesForAttributeContext() {
        assertThat(escapeHtml("'\" onmouseover=\"alert(1)'"))
                .isEqualTo("&quot; onmouseover=&quot;alert(1)");
    }

    @Test
    void escapesSingleQuotesForAttributeContext() {
        assertThat(escapeHtml("\"' onmouseover='alert(1)\""))
                .isEqualTo("&#39; onmouseover=&#39;alert(1)");
    }

    @Test
    void handlesNullAndUndefined() {
        assertThat(escapeHtml("null")).isEqualTo("");
        assertThat(escapeHtml("undefined")).isEqualTo("");
    }

    @Test
    void preservesNumericZero() {
        assertThat(escapeHtml("0")).isEqualTo("0");
    }
}
