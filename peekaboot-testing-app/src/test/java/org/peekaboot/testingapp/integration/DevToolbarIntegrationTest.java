package org.peekaboot.testingapp.integration;

import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for dev toolbar filter behavior.
 * Boots the real sample app; the toolbar filter and its supporting beans come
 * from the real {@code DevToolbarAutoConfiguration}. SharedToolbarTestConfig
 * only supplies a deterministic hand-written {@code Tracer} and a small-capacity
 * TraceStore, so the real production wiring is what's actually under test.
 *
 * For auto-configuration ordering tests, see DevToolbarAutoConfigurationIntegrationTest
 * in the peekaboot-spring-boot-autoconfigure module.
 */
@SpringBootTest(
    classes = {TestingApp.class, SharedToolbarTestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class DevToolbarIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .build();
        personRepository.deleteAll();

        Person person = new Person();
        person.setFirstName("Test");
        person.setLastName("User");
        person.setEmail("test@example.com");
        personRepository.save(person);
    }

    private String getPersonsHtml() {
        return restClient.get()
            .uri("/persons")
            .accept(MediaType.TEXT_HTML)
            .retrieve()
            .body(String.class);
    }

    @Test
    void toolbarShouldBeInjectedIntoHtmlResponse() {
        assertThat(getPersonsHtml()).contains("<!-- Peekaboot Dev Toolbar -->");
    }

    @Test
    void toolbarShouldContainExpectedStatusCode() {
        assertThat(getPersonsHtml()).contains("\"status\":200");
    }

    @Test
    void toolbarShouldContainRequestMethod() {
        assertThat(getPersonsHtml()).contains("\"method\":\"GET\"");
    }

    @Test
    void toolbarShouldContainRequestPath() {
        assertThat(getPersonsHtml()).contains("\"path\":\"/persons\"");
    }

    @Test
    void toolbarShouldContainTraceId() {
        // traceId can be null in test environment without full tracing setup
        assertThat(getPersonsHtml()).matches("(?s).*\"traceId\":(null|\"[a-f0-9]+\").*");
    }

    @Test
    void toolbarShouldContainBasePath() {
        assertThat(getPersonsHtml()).contains("\"basePath\":\"/peekaboot\"");
    }

    @Test
    void toolbarShouldNotBeInjectedForJsonResponses() {
        String response = restClient.get()
            .uri("/peekaboot/api/features")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        assertThat(response).doesNotContain("<!-- Peekaboot Dev Toolbar -->");
    }

    @Test
    void toolbarShouldNotBeInjectedForPeekabootEndpoints() {
        String response = restClient.get()
            .uri("/peekaboot/api/actuator/all/insights")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        assertThat(response).doesNotContain("<!-- Peekaboot Dev Toolbar -->");
    }
}
