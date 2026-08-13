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
    private CollectingJavaScriptErrorListener jsErrors;

    @BeforeEach
    void setUp() throws IOException {
        webClient = new WebClient();
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
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
        if (jsErrors != null) {
            jsErrors.assertNoUnexpectedErrors();
        }
    }

    private String escapeHtml(String jsArgumentLiteral) {
        ScriptResult result = page.executeJavaScript("PeekabootUtils.escapeHtml(" + jsArgumentLiteral + ")");
        return (String) result.getJavaScriptResult();
    }

    private String formatDurationMs(String jsArgumentLiteral) {
        ScriptResult result = page.executeJavaScript("PeekabootUtils.formatDurationMs(" + jsArgumentLiteral + ")");
        return (String) result.getJavaScriptResult();
    }

    private String getDurationClass(String jsArgumentLiteral) {
        ScriptResult result = page.executeJavaScript("PeekabootUtils.getDurationClass(" + jsArgumentLiteral + ")");
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

    @Test
    void formatDurationMs_handlesNull() {
        assertThat(formatDurationMs("null")).isEqualTo("-");
    }

    @Test
    void formatDurationMs_formatsSubMillisecondAsLessThanOneMs() {
        assertThat(formatDurationMs("0.5")).isEqualTo("<1ms");
    }

    @Test
    void formatDurationMs_roundsMillisecondsBelowOneSecond() {
        assertThat(formatDurationMs("500.4")).isEqualTo("500ms");
    }

    @Test
    void formatDurationMs_formatsSecondsBelowOneMinute() {
        assertThat(formatDurationMs("1500")).isEqualTo("1.50s");
    }

    @Test
    void formatDurationMs_formatsMinutesAtOrAboveOneMinute() {
        assertThat(formatDurationMs("90000")).isEqualTo("1.50m");
    }

    @Test
    void getDurationClass_returnsVerySlowAboveFiveHundredMs() {
        assertThat(getDurationClass("501")).isEqualTo("very-slow");
    }

    @Test
    void getDurationClass_returnsSlowAboveOneHundredMs() {
        assertThat(getDurationClass("101")).isEqualTo("slow");
    }

    @Test
    void getDurationClass_returnsEmptyForFastDurations() {
        assertThat(getDurationClass("100")).isEqualTo("");
    }
}
