package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Asserts on the toolbar-data injected into the server-rendered /persons page - not on
 * any overlay/toolbar JS behaviour, so a plain HTTP client is enough; no browser needed.
 *
 * These four tests used to drive HtmlUnit. Everything HtmlUnit-specific that lived here
 * (toolbarHostShouldBeVisible, toolbarScriptShouldLazyLoadTraceDetailScript,
 * clickingToolbarShouldActuallyLazyLoadAndOpenTraceDetailScript, and the white-box
 * assertion on toolbar.js's source text) was already removed in an earlier task - see
 * org.peekaboot.testingapp.ui.ToolbarIT for the real-browser coverage that superseded
 * them, in particular clickingTheBarOpensTheTraceOverlay.
 */
@SpringBootTest(
        classes = {TestingApp.class, SharedToolbarTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
// READ side of the shared TraceStore: pins its own traces by id, which tolerates
// concurrent readers but not DashboardTraceViewIT's per-test clear (the WRITE side).
@ResourceLock(value = "shared-toolbar-trace-store", mode = ResourceAccessMode.READ)
class TraceDetailOverlayIT {

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private PersonRepository personRepository;

    @BeforeEach
    void setUp() {
        personRepository.deleteAll();
        Person person = new Person();
        person.setFirstName("Test");
        person.setLastName("User");
        person.setEmail("test@example.com");
        personRepository.save(person);
    }

    @Test
    void injectedPageShouldLoadExternalToolbarScript() {
        assertThat(getPersonsPageHtml()).contains("/peekaboot/ui/toolbar/toolbar.js");
    }

    @Test
    void traceIdShouldMatchApiResponse() {
        String pageContent = getPersonsPageHtml();

        // Extract traceId from the peekaboot-toolbar-data script element
        Pattern traceIdPattern = Pattern.compile("\"traceId\":(null|\"([a-f0-9]+)\")");
        Matcher matcher = traceIdPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find traceId in toolbar data").isTrue();

        String traceIdValue = matcher.group(1);
        // traceId can be null in test environment or a hex string
        assertThat(traceIdValue).matches("null|\"[a-f0-9]+\"");
    }

    @Test
    void toolbarShouldShowStatusCode() {
        // The toolbar JSON should contain status code
        assertThat(getPersonsPageHtml()).contains("\"status\":200");
    }

    @Test
    void toolbarShouldShowBasePath() {
        String pageContent = getPersonsPageHtml();

        // Extract basePath from toolbar JSON
        Pattern basePathPattern = Pattern.compile("\"basePath\":\"([^\"]+)\"");
        Matcher matcher = basePathPattern.matcher(pageContent);

        assertThat(matcher.find()).as("Should find basePath in toolbar data").isTrue();

        String basePath = matcher.group(1);
        assertThat(basePath).as("BasePath should be /peekaboot").isEqualTo("/peekaboot");
    }

    private String getPersonsPageHtml() {
        return restTestClient
                .get()
                .uri("/persons")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }
}
