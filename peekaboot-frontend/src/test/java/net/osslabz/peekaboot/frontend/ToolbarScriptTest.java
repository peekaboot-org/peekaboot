package net.osslabz.peekaboot.frontend;

import org.htmlunit.MockWebConnection;
import org.htmlunit.ScriptResult;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the real toolbar.js in a browser engine against the same
 * bootstrap HTML that DevToolbarFilter injects.
 */
class ToolbarScriptTest {

    /**
     * HtmlUnit does not implement Element.attachShadow; emulate it with a
     * light-DOM stand-in so the toolbar script can run. This shims a missing
     * browser API, not toolbar logic.
     */
    private static final String ATTACH_SHADOW_SHIM = """
            Element.prototype.attachShadow = function() {
                this.getElementById = function(id) { return this.querySelector('#' + id); };
                return this;
            };
            """;

    private WebClient webClient;

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    private HtmlPage loadPageWithToolbar(String dataJson) throws IOException {
        webClient = new WebClient();
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse(
                "<html><head></head><body>"
                + "<script id=\"peekaboot-toolbar-data\" type=\"application/json\">" + dataJson + "</script>"
                + "</body></html>");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");
        page.executeJavaScript(ATTACH_SHADOW_SHIM);

        String toolbarJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/toolbar/toolbar.js"));
        page.executeJavaScript(toolbarJs);
        return page;
    }

    @Test
    void createsToolbarHostAndGlobalApi() throws IOException {
        HtmlPage page = loadPageWithToolbar(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":null,\"basePath\":\"/peekaboot\"}");

        assertThat(page.getElementById("peekaboot-toolbar-host")).isNotNull();

        ScriptResult api = page.executeJavaScript("typeof window.__peekaboot");
        assertThat(api.getJavaScriptResult()).isEqualTo("object");

        ScriptResult basePath = page.executeJavaScript("window.__peekaboot.basePath");
        assertThat(basePath.getJavaScriptResult()).isEqualTo("/peekaboot");

        ScriptResult loadTrace = page.executeJavaScript("typeof window.__peekaboot.loadTrace");
        assertThat(loadTrace.getJavaScriptResult()).isEqualTo("function");
    }

    @Test
    void doesNotInitializeTwice() throws IOException {
        HtmlPage page = loadPageWithToolbar(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":null,\"basePath\":\"/peekaboot\"}");

        String toolbarJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/toolbar/toolbar.js"));
        page.executeJavaScript(toolbarJs);

        ScriptResult hostCount = page.executeJavaScript(
                "document.querySelectorAll('#peekaboot-toolbar-host').length");
        assertThat(((Number) hostCount.getJavaScriptResult()).intValue()).isEqualTo(1);
    }

    @Test
    void idleModeWrapsWindowFetchWithInterceptor() throws IOException {
        webClient = new WebClient();
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse(
                "<html><head></head><body>"
                + "<script id=\"peekaboot-toolbar-data\" type=\"application/json\">"
                + "{\"idle\":true,\"basePath\":\"/peekaboot\"}"
                + "</script>"
                + "</body></html>");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");

        page.executeJavaScript(ATTACH_SHADOW_SHIM);
        page.executeJavaScript("window.__originalFetchBefore = window.fetch;");
        String toolbarJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/toolbar/toolbar.js"));
        page.executeJavaScript(toolbarJs);

        ScriptResult wrapped = page.executeJavaScript("window.fetch !== window.__originalFetchBefore");
        assertThat(wrapped.getJavaScriptResult()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void regularModeDoesNotWrapWindowFetch() throws IOException {
        webClient = new WebClient();
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse(
                "<html><head></head><body>"
                + "<script id=\"peekaboot-toolbar-data\" type=\"application/json\">"
                + "{\"method\":\"GET\",\"path\":\"/x\",\"status\":200,\"traceId\":null,\"basePath\":\"/peekaboot\"}"
                + "</script>"
                + "</body></html>");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");

        page.executeJavaScript(ATTACH_SHADOW_SHIM);
        page.executeJavaScript("window.__originalFetchBefore = window.fetch;");
        String toolbarJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/toolbar/toolbar.js"));
        page.executeJavaScript(toolbarJs);

        ScriptResult wrapped = page.executeJavaScript("window.fetch === window.__originalFetchBefore");
        assertThat(wrapped.getJavaScriptResult()).isEqualTo(Boolean.TRUE);
    }
}
