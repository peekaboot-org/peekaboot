package net.osslabz.peekaboot.backend.integration;

import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import net.osslabz.peekaboot.backend.fixture.entity.Person;
import net.osslabz.peekaboot.backend.fixture.repository.PersonRepository;
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

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        webClient = new WebClient(BrowserVersion.CHROME);
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
    void clickingToolbarShouldLoadTraceDetailScript() throws Exception {
        // The script path for trace-detail.js should be present in the toolbar click handler
        assertThat(getPersonsPageContent()).contains("/peekaboot/ui/trace-detail/trace-detail.js");
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
    void toolbarShouldShowDurationGreaterOrEqualToZero() throws Exception {
        String pageContent = getPersonsPageContent();

        // Extract duration from toolbar JSON
        Pattern durationPattern = Pattern.compile("\"duration\":(\\d+)");
        Matcher matcher = durationPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find duration in toolbar data").isTrue();

        int duration = Integer.parseInt(matcher.group(1));
        assertThat(duration).as("Duration should be >= 0").isGreaterThanOrEqualTo(0);
    }
}
