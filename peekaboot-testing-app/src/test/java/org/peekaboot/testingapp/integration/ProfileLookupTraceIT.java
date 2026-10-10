package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.profile.ProfileCall;
import org.peekaboot.testingapp.profile.ProfileClient;
import org.peekaboot.testingapp.profile.ProfileLookup;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/**
 * The person page's profile lookup against a real go-httpbin: the call is a CLIENT span of the
 * page's own trace, the server echoes the traceparent it received, and a failing or slow profile
 * service shows up where the dashboard files failures and slow traces.
 */
// @Testcontainers must stay first: JUnit runs afterAll in reverse, so the context closes before the container stops.
@Testcontainers
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileLookupTraceIT {

    @Container
    static final GenericContainer<?> HTTPBIN =
            new GenericContainer<>("ghcr.io/mccutchen/go-httpbin:2.25.0").withExposedPorts(8080);

    @DynamicPropertySource
    static void profileServiceOnHttpbin(DynamicPropertyRegistry registry) {
        registry.add(
                "app.profile-service.url", () -> "http://" + HTTPBIN.getHost() + ":" + HTTPBIN.getMappedPort(8080));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private RestClient.Builder restClientBuilder;

    private TraceApiClient traces;

    private Person withEmail;

    private Person withoutEmail;

    @BeforeEach
    void seedPersons() {
        traces = new TraceApiClient(port);
        withEmail = save("Grace", "Hopper", "grace@example.com");
        withoutEmail = save("Alan", "Turing", null);
    }

    @Test
    void theLookupIsAClientSpanOfThePageTraceAndCarriesItsTraceparent() {
        ResponseEntity<String> page = personPage(withEmail, "");
        String traceId = TraceApiClient.traceIdOf(page.getHeaders());

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(httpClientSpans(trace))
                .singleElement()
                .satisfies(
                        client -> assertThat(client.path("tags").valueStream().map(tag -> tag.asString("")))
                                .as("the client span names the endpoint it called, under whichever tag key")
                                .anySatisfy(tag -> assertThat(tag).contains("/anything/profiles/")));
        assertThat(page.getBody())
                .as("go-httpbin echoes the traceparent it received, which must carry this page's trace id")
                .contains("<dd id=\"profile-traceparent\">00-" + traceId + "-")
                .contains("<dd id=\"profile-email\">grace@example.com</dd>");
    }

    @Test
    void anUnavailableProfileServiceFailsTheClientSpanAndThePageStillRenders() {
        ResponseEntity<String> page;
        try (LogCapture logs = LogCapture.attach(ProfileClient.class)) {
            page = personPage(withEmail, "?profile=unavailable");

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("profile lookup for person " + withEmail.getId())
                        .contains("503");
            });
        }

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("<p id=\"profile-status\">unreachable</p>");
        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(page.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(httpClientSpans(trace))
                .singleElement()
                .satisfies(
                        client -> assertThat(client.path("status").asString("")).isEqualTo("ERROR"));
    }

    @Test
    void aSlowProfileServicePutsThePageInTheSlowBucket() {
        String traceId =
                TraceApiClient.traceIdOf(personPage(withEmail, "?profile=slow").getHeaders());

        traces.awaitTraceInBucket("slow", traceId);

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(httpClientSpans(trace))
                .singleElement()
                .satisfies(
                        client -> assertThat(client.path("durationMs").asLong()).isGreaterThanOrEqualTo(1200L));
    }

    @Test
    void aPersonWithoutAnEmailStillGetsTheirProfile() {
        ResponseEntity<String> page = personPage(withoutEmail, "");

        assertThat(page.getBody()).contains("<dd id=\"profile-email\"></dd>").doesNotContain("id=\"profile-status\"");
    }

    @Test
    void aProfileServiceRefusingConnectionsReadsAsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ProfileClient refused = new ProfileClient(restClientBuilder, "http://127.0.0.1:" + closedPort);

        try (LogCapture logs = LogCapture.attach(ProfileClient.class)) {
            assertThat(refused.lookup(withEmail.getId(), withEmail.getEmail(), ProfileCall.LOOKUP))
                    .isEqualTo(ProfileLookup.unreachable());

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("profile lookup for person " + withEmail.getId());
            });
        }
    }

    /** JDBC spans are CLIENT spans too; only the outbound HTTP call carries an http.url. */
    private static List<JsonNode> httpClientSpans(JsonNode trace) {
        return SpanTree.spansOfKind(trace, "CLIENT").stream()
                .filter(span -> span.path("tags").has("http.url"))
                .toList();
    }

    private Person save(String firstName, String lastName, String email) {
        Person person = new Person();
        person.setFirstName(firstName);
        person.setLastName(lastName);
        person.setEmail(email);
        return personRepository.save(person);
    }

    private ResponseEntity<String> personPage(Person person, String query) {
        return traces.restClient()
                .get()
                .uri("/persons/" + person.getId() + query)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .toEntity(String.class);
    }
}
