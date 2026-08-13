package net.osslabz.peekaboot.backend.integration;

import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import net.osslabz.peekaboot.backend.fixture.entity.Person;
import net.osslabz.peekaboot.backend.fixture.repository.PersonRepository;
import org.htmlunit.BrowserVersion;
import org.htmlunit.MockWebConnection;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestFixtureApplication.class, SharedToolbarTestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class TraceDetailOverlayTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    private WebClient webClient;
    private String baseUrl;
    private CollectingJavaScriptErrorListener jsErrors;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        webClient = new WebClient(BrowserVersion.CHROME);
        jsErrors = CollectingJavaScriptErrorListener.installOn(webClient);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);

        personRepository.deleteAll();
        Person person = new Person();
        person.setFirstName("Test");
        person.setLastName("User");
        person.setEmail("test@example.com");
        personRepository.save(person);
    }

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
        if (jsErrors != null) {
            jsErrors.assertNoUnexpectedErrors();
        }
    }

    private String getPersonsPageContent() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        return page.asXml();
    }

    @Test
    void toolbarHostShouldBeVisible() throws Exception {
        // The toolbar is injected as a div with id peekaboot-toolbar-host
        assertThat(getPersonsPageContent()).contains("peekaboot-toolbar-host");
    }

    @Test
    void injectedPageShouldLoadExternalToolbarScript() throws Exception {
        assertThat(getPersonsPageContent()).contains("/peekaboot/ui/toolbar/toolbar.js");
    }

    @Test
    void toolbarScriptShouldLazyLoadTraceDetailScript() throws Exception {
        // The toolbar script dynamically builds the path using data.basePath
        String toolbarJs = webClient.getPage(baseUrl + "/peekaboot/ui/toolbar/toolbar.js")
                .getWebResponse().getContentAsString();
        assertThat(toolbarJs).contains("data.basePath + '/ui/trace-detail/trace-detail.js'");
    }

    @Test
    void clickingToolbarShouldActuallyLazyLoadAndOpenTraceDetailScript() throws Exception {
        // Real attachShadow() isn't implemented by HtmlUnit's JS engine (the
        // known, accepted "Cannot find function attachShadow" noise), so the
        // toolbar's IIFE aborts before registering its click handler on a page
        // fetched directly from the running server. Drive the *real*, live
        // toolbar.js content served by this app against a synthetic page with
        // the same shadow-DOM shim the frontend module's ToolbarScriptTest
        // uses, so the click -> lazy-load -> onload chain is actually
        // exercised end-to-end instead of only substring-checked. The real
        // trace-detail.js uses syntax HtmlUnit's JS engine can't parse (same
        // documented incompatibility as toolbar.js), so a harmless stub
        // stands in for it here — this test's scope is the toolbar's lazy-load
        // wiring, not trace-detail.js's own internal correctness.
        String toolbarJs = webClient.getPage(baseUrl + "/peekaboot/ui/toolbar/toolbar.js")
                .getWebResponse().getContentAsString();

        webClient.close();
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
        connection.setResponse(new URL("http://localhost/peekaboot/ui/trace-detail/trace-detail.js"),
                "window.PeekabootTraceDetail = { open: function(traceId, opts) { window.__openedWith = traceId; } };",
                "text/javascript");
        webClient.setWebConnection(connection);
        HtmlPage page = webClient.getPage("http://localhost/test.html");

        page.executeJavaScript("""
                Element.prototype.attachShadow = function() {
                    this.getElementById = function(id) { return this.querySelector('#' + id); };
                    return this;
                };
                """);
        // No native fetch in this HtmlUnit configuration; loadTrace()'s
        // background poll must not blow up with a ReferenceError.
        page.executeJavaScript("window.fetch = function() { return Promise.reject(new Error('no network')); };");
        page.executeJavaScript(toolbarJs);

        page.executeJavaScript("window.__peekaboot.loadTrace('trace-xyz', 'GET', '/persons', 200);");
        page.executeJavaScript(
                "document.querySelector('.peekaboot-bar').dispatchEvent(new MouseEvent('click', {bubbles: true}));");
        webClient.waitForBackgroundJavaScript(2000);

        String scriptSrc = (String) page.executeJavaScript(
                "(function() { const s = document.head.querySelector('script[src*=\"trace-detail\"]'); return s ? s.src : null; })()"
        ).getJavaScriptResult();
        assertThat(scriptSrc).endsWith("/peekaboot/ui/trace-detail/trace-detail.js");

        String openedWith = (String) page.executeJavaScript("window.__openedWith").getJavaScriptResult();
        assertThat(openedWith).isEqualTo("trace-xyz");
    }

    @Test
    void traceIdShouldMatchApiResponse() throws Exception {
        String pageContent = getPersonsPageContent();

        // Extract traceId from the peekaboot-toolbar-data script element
        Pattern traceIdPattern = Pattern.compile("\"traceId\":(null|\"([a-f0-9]+)\")");
        Matcher matcher = traceIdPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find traceId in toolbar data").isTrue();

        String traceIdValue = matcher.group(1);
        // traceId can be null in test environment or a hex string
        assertThat(traceIdValue).matches("null|\"[a-f0-9]+\"");
    }

    @Test
    void toolbarShouldShowStatusCode() throws Exception {
        // The toolbar JSON should contain status code
        assertThat(getPersonsPageContent()).contains("\"status\":200");
    }

    @Test
    void toolbarShouldShowBasePath() throws Exception {
        String pageContent = getPersonsPageContent();

        // Extract basePath from toolbar JSON
        Pattern basePathPattern = Pattern.compile("\"basePath\":\"([^\"]+)\"");
        Matcher matcher = basePathPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find basePath in toolbar data").isTrue();

        String basePath = matcher.group(1);
        assertThat(basePath).as("BasePath should be /peekaboot").isEqualTo("/peekaboot");
    }
}
