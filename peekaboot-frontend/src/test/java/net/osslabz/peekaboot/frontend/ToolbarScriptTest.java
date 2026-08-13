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
    private CollectingJavaScriptErrorListener jsErrors;

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
        if (jsErrors != null) {
            jsErrors.assertNoUnexpectedErrors();
        }
    }

    private HtmlPage loadPageWithToolbar(String dataJson) throws IOException {
        webClient = new WebClient();
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
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

    /**
     * Loads the toolbar with a stubbed {@code window.fetch} (and an immediate,
     * synchronous {@code window.setTimeout}) installed before toolbar.js runs,
     * so the retry/backoff and rendering logic in {@code loadTrace}/{@code fetchTrace}
     * can be driven deterministically instead of waiting on real timers.
     */
    private HtmlPage loadPageWithFetchStub(String dataJson, String fetchStubJs) throws IOException {
        webClient = new WebClient();
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse(
                "<html><head></head><body>"
                + "<script id=\"peekaboot-toolbar-data\" type=\"application/json\">" + dataJson + "</script>"
                + "</body></html>");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");
        page.executeJavaScript(ATTACH_SHADOW_SHIM);
        page.executeJavaScript("window.setTimeout = function(fn) { fn(); };");
        page.executeJavaScript(fetchStubJs);

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
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
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
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
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

    @Test
    void updateToolbarRendersDurationClassQueryCountAndControllerName() throws IOException {
        HtmlPage page = loadPageWithFetchStub(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":\"abc123\",\"basePath\":\"/peekaboot\"}",
                """
                window.fetch = function(url) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: function() {
                            return Promise.resolve({
                                rootSpan: {},
                                durationMs: 600,
                                summary: {
                                    spans: { count: 1 },
                                    queries: { count: 2, totalDurationMs: 150 },
                                    logs: { errorCount: 1, warnCount: 2 }
                                },
                                queries: [1, 2],
                                httpExchange: {
                                    request: { controller: { class: "com.example.UserController", method: "list" } }
                                }
                            });
                        }
                    });
                };
                """);

        webClient.waitForBackgroundJavaScript(2000);

        String metricsHtml = (String) page.executeJavaScript(
                "document.getElementById('pb-metrics').innerHTML").getJavaScriptResult();
        assertThat(metricsHtml).contains("600ms");
        assertThat(metricsHtml).contains("peekaboot-stat error");
        assertThat(metricsHtml).contains("2 queries");
        // queryDuration (150ms) > 100 triggers the query stat's own "warn" class
        assertThat(metricsHtml).contains("150ms");
        assertThat(metricsHtml).contains("peekaboot-stat warn");
        assertThat(metricsHtml).contains("❗1 err");
        assertThat(metricsHtml).contains("⚠2 warn");

        String controllerText = (String) page.executeJavaScript(
                "document.getElementById('pb-controller').textContent").getJavaScriptResult();
        assertThat(controllerText).isEqualTo("→ UserController.list");
    }

    @Test
    void updateToolbarUsesWarnDurationClassAboveOneHundredMs() throws IOException {
        HtmlPage page = loadPageWithFetchStub(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":\"abc123\",\"basePath\":\"/peekaboot\"}",
                """
                window.fetch = function(url) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: function() {
                            return Promise.resolve({
                                rootSpan: {},
                                durationMs: 150,
                                summary: { spans: { count: 1 }, queries: { count: 0 }, logs: {} }
                            });
                        }
                    });
                };
                """);

        webClient.waitForBackgroundJavaScript(2000);

        String metricsHtml = (String) page.executeJavaScript(
                "document.getElementById('pb-metrics').innerHTML").getJavaScriptResult();
        assertThat(metricsHtml).contains("150ms");
        assertThat(metricsHtml).contains("peekaboot-stat warn");
        assertThat(metricsHtml).doesNotContain("queries");
    }

    @Test
    void showPendingRendersWhenFetchRejects() throws IOException {
        HtmlPage page = loadPageWithFetchStub(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":\"abc123\",\"basePath\":\"/peekaboot\"}",
                "window.fetch = function(url) { return Promise.reject(new Error('network down')); };");

        webClient.waitForBackgroundJavaScript(2000);

        String metricsHtml = (String) page.executeJavaScript(
                "document.getElementById('pb-metrics').innerHTML").getJavaScriptResult();
        assertThat(metricsHtml).contains("peekaboot-pending");
    }

    @Test
    void fetchTraceRetriesUntilTraceIsComplete() throws IOException {
        // First poll returns a trace with no rootSpan/summary (incomplete); the
        // second poll returns a complete trace. The synchronous setTimeout stub
        // in loadPageWithFetchStub lets the retry fire immediately.
        HtmlPage page = loadPageWithFetchStub(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":\"abc123\",\"basePath\":\"/peekaboot\"}",
                """
                window.__fetchCallCount = 0;
                window.fetch = function(url) {
                    window.__fetchCallCount++;
                    const complete = window.__fetchCallCount > 1;
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: function() {
                            return Promise.resolve(complete
                                ? { rootSpan: {}, durationMs: 42, summary: { spans: { count: 1 }, queries: { count: 0 }, logs: {} } }
                                : {});
                        }
                    });
                };
                """);

        webClient.waitForBackgroundJavaScript(2000);

        // First poll (incomplete) schedules exactly one retry; the second
        // poll is complete and stops the loop, so exactly 2 fetch() calls occur.
        int fetchCallCount = ((Number) page.executeJavaScript("window.__fetchCallCount").getJavaScriptResult()).intValue();
        assertThat(fetchCallCount).isEqualTo(2);

        String metricsHtml = (String) page.executeJavaScript(
                "document.getElementById('pb-metrics').innerHTML").getJavaScriptResult();
        assertThat(metricsHtml).contains("42ms");
    }

    @Test
    void fetchTraceRetriesOn404UntilBackoffCapThenShowsPending() throws IOException {
        HtmlPage page = loadPageWithFetchStub(
                "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":\"abc123\",\"basePath\":\"/peekaboot\"}",
                """
                window.__fetchCallCount = 0;
                window.fetch = function(url) {
                    window.__fetchCallCount++;
                    return Promise.resolve({ ok: false, status: 404 });
                };
                """);

        webClient.waitForBackgroundJavaScript(5000);

        // retryDelay starts at 250 and doubles each time (250, 500, 1000, 2000,
        // 4000, 8000, 16000, 32000): the loop keeps retrying while totalDelay
        // (the running sum before that iteration's delay) is still < 32000,
        // which holds through the 8th call (totalDelay=31750), giving a 9th
        // and final call whose totalDelay(63750) trips the cap and gives up.
        int fetchCallCount = ((Number) page.executeJavaScript("window.__fetchCallCount").getJavaScriptResult()).intValue();
        assertThat(fetchCallCount).isEqualTo(9);

        String metricsHtml = (String) page.executeJavaScript(
                "document.getElementById('pb-metrics').innerHTML").getJavaScriptResult();
        assertThat(metricsHtml).contains("peekaboot-pending");
    }

    @Test
    void clickLazyLoadsTraceDetailScriptAndOpensItOnLoad() throws IOException {
        webClient = new WebClient();
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
        webClient.getOptions().setCssEnabled(false);
        MockWebConnection connection = new MockWebConnection();
        connection.setDefaultResponse(
                "<html><head></head><body>"
                + "<script id=\"peekaboot-toolbar-data\" type=\"application/json\">"
                + "{\"method\":\"GET\",\"path\":\"/persons\",\"status\":200,\"traceId\":null,\"basePath\":\"/peekaboot\"}"
                + "</script>"
                + "</body></html>");
        // The click handler lazy-loads this script as a real <script src> element;
        // serve harmless JS so the load succeeds and onload fires.
        connection.setResponse(
                new java.net.URL("http://localhost/peekaboot/ui/trace-detail/trace-detail.js"),
                "window.PeekabootTraceDetail = { open: function(traceId, opts) { window.__openedWith = traceId; } };",
                "text/javascript");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");
        page.executeJavaScript(ATTACH_SHADOW_SHIM);

        String toolbarJs = Files.readString(Path.of("src/main/resources/static/peekaboot/ui/toolbar/toolbar.js"));
        page.executeJavaScript(toolbarJs);

        // This HtmlUnit configuration has no native fetch; stub it so the
        // background poll loadTrace schedules doesn't blow up with a
        // ReferenceError (its rejection is swallowed by fetchTrace's .catch).
        page.executeJavaScript("window.fetch = function() { return Promise.reject(new Error('no network')); };");
        // Simulate an active trace without going through the async fetch flow;
        // loadTrace sets currentTraceId synchronously before scheduling any fetch.
        page.executeJavaScript("window.__peekaboot.loadTrace('trace-xyz', 'GET', '/persons', 200);");

        String scriptSelector =
                "document.head.querySelector('script[src*=\"trace-detail\"]')";
        Boolean scriptPresentBeforeClick = (Boolean) page.executeJavaScript(
                scriptSelector + " !== null").getJavaScriptResult();
        assertThat(scriptPresentBeforeClick).as("script absent before click").isFalse();

        page.executeJavaScript(
                "document.querySelector('.peekaboot-bar').dispatchEvent(new MouseEvent('click', {bubbles: true}));");
        webClient.waitForBackgroundJavaScript(2000);

        String scriptSrc = (String) page.executeJavaScript(
                "(function() { const s = " + scriptSelector + "; return s ? s.src : null; })()"
        ).getJavaScriptResult();
        assertThat(scriptSrc).as("script present after click").endsWith("/peekaboot/ui/trace-detail/trace-detail.js");

        String openedWith = (String) page.executeJavaScript("window.__openedWith").getJavaScriptResult();
        assertThat(openedWith).isEqualTo("trace-xyz");
    }
}
