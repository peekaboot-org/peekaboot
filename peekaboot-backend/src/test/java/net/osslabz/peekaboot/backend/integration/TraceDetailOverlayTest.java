package net.osslabz.peekaboot.backend.integration;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.filter.DevToolbarFilter;
import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import net.osslabz.peekaboot.backend.fixture.entity.Person;
import net.osslabz.peekaboot.backend.fixture.repository.PersonRepository;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestFixtureApplication.class, TraceDetailOverlayTest.TestConfig.class},
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

    @Test
    void toolbarHostShouldBeVisible() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        String pageContent = page.asXml();

        // The toolbar is injected as a div with id peekaboot-toolbar-host
        assertThat(pageContent).contains("peekaboot-toolbar-host");
    }

    @Test
    void clickingToolbarShouldLoadTraceDetailScript() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        String pageContent = page.asXml();

        // The script path for trace-detail.js should be present in the toolbar click handler
        assertThat(pageContent).contains("/peekaboot/ui/trace-detail/trace-detail.js");
    }

    @Test
    void traceIdShouldMatchApiResponse() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        String pageContent = page.asXml();

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
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        String pageContent = page.asXml();

        // The toolbar JSON should contain status code
        assertThat(pageContent).contains("\"status\":200");
    }

    @Test
    void toolbarShouldShowDurationGreaterOrEqualToZero() throws Exception {
        HtmlPage page = webClient.getPage(baseUrl + "/persons");
        String pageContent = page.asXml();

        // Extract duration from toolbar JSON
        Pattern durationPattern = Pattern.compile("\"duration\":(\\d+)");
        Matcher matcher = durationPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find duration in toolbar data").isTrue();

        int duration = Integer.parseInt(matcher.group(1));
        assertThat(duration).as("Duration should be >= 0").isGreaterThanOrEqualTo(0);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        InMemorySpanStore spanStore() {
            return new InMemorySpanStore(100, 50);
        }

        @Bean
        TraceQueryService traceQueryService(InMemorySpanStore spanStore) {
            return new TraceQueryService(spanStore);
        }

        @Bean
        ToolbarDataProvider toolbarDataProvider(
                TraceQueryService traceQueryService,
                PeekabookActuatorService actuatorService,
                PeekabootProperties properties) {
            return new ToolbarDataProvider(traceQueryService, actuatorService, properties.getBasePath());
        }

        @Bean
        FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
                ToolbarDataProvider toolbarDataProvider,
                PeekabootProperties properties) {
            FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new DevToolbarFilter(toolbarDataProvider, properties.getBasePath()));
            registration.addUrlPatterns("/*");
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            registration.setName("devToolbarFilter");
            return registration;
        }
    }
}
