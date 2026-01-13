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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestFixtureApplication.class, DevToolbarIntegrationTest.DevToolbarTestConfiguration.class},
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
    void toolbarShouldContainQueryCount() {
        assertThat(getPersonsHtml()).matches("(?s).*\"queryCount\":-?\\d+.*");
    }

    @Test
    void toolbarShouldContainDuration() {
        assertThat(getPersonsHtml()).matches("(?s).*\"duration\":\\d+.*");
    }

    @Test
    void toolbarShouldContainHealthStatus() {
        assertThat(getPersonsHtml()).matches("(?s).*\"health\":\"(UP|DOWN|UNKNOWN)\".*");
    }

    @Test
    void toolbarShouldContainDashboardUrl() {
        assertThat(getPersonsHtml()).contains("\"/peekaboot/\"");
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

    @TestConfiguration
    static class DevToolbarTestConfiguration {

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
