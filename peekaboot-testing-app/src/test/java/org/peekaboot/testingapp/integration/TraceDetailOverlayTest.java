package org.peekaboot.testingapp.integration;

import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestingApp.class, SharedToolbarTestConfig.class},
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

    @Test
    void injectedPageShouldLoadExternalToolbarScript() throws Exception {
        assertThat(getPersonsPageContent()).contains("/peekaboot/ui/toolbar/toolbar.js");
    }

    /*
     * toolbarHostShouldBeVisible, toolbarScriptShouldLazyLoadTraceDetailScript and
     * clickingToolbarShouldActuallyLazyLoadAndOpenTraceDetailScript used to live here.
     *
     * toolbarHostShouldBeVisible asserted that the raw server HTML (via page.asXml(), no
     * JS executed) already contained "peekaboot-toolbar-host". That was only ever true by
     * accident: the toolbar host div is created by toolbar.js at runtime, not present in
     * the server-rendered HTML at all - the assertion passed previously only because
     * HtmlUnit executed the old classic toolbar.js far enough to append the host div
     * before failing on the unimplemented attachShadow(). Now that toolbar.js is
     * `<script type="module">`, HtmlUnit's engine does not execute it at all (no host div,
     * no error either - the element is simply not run), so there is no longer any HtmlUnit
     * signal to assert on here.
     *
     * toolbarScriptShouldLazyLoadTraceDetailScript asserted that toolbar.js's source text
     * contained the literal string "data.basePath + '/ui/trace-detail/trace-detail.js'" -
     * a white-box check on implementation text, not behaviour, and toolbar.js now opens
     * the overlay via `await import('../trace-detail/trace-detail.js')` instead, so the
     * string is gone.
     *
     * clickingToolbarShouldActuallyLazyLoadAndOpenTraceDetailScript loaded the real
     * toolbar.js source into an HtmlUnit page via page.executeJavaScript(toolbarJs) and
     * drove window.__peekaboot.loadTrace() and a '.peekaboot-bar' click directly.
     * toolbar.js is now an ES module (top-level import statements, a .pk-toolbar class,
     * no window.__peekaboot), which HtmlUnit's JS engine cannot parse as a classic script.
     *
     * All three are superseded by org.peekaboot.testingapp.ui.ToolbarTest - in particular
     * clickingTheBarOpensTheTraceOverlay - real Playwright tests that drive the actual
     * served toolbar.js/trace-detail.js in a real browser end to end, including proving
     * the host div appears (every ToolbarTest test waits on #peekaboot-toolbar-host).
     */

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

    private String getPersonsPageContent() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        return page.asXml();
    }
}
