package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.DigestFixtureController;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The health report over real HTTP, with a mail target nothing listens on, so the application's
 * health is DOWN and the report answers 503. The context is this class's own, so its counts and
 * failures are its own.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.mail.host=127.0.0.1", "spring.mail.port=1"})
class HealthEndpointIT extends TestingAppITBase {

    private static final String HEALTH = "/peekaboot/api/health";

    private static final String SECRET_URL = "redis://cache:s3cret@cache.internal:6379";

    @Autowired
    private MeterRegistry meterRegistry;

    private ApiContract contract;

    private LogCapture mailLog;

    @BeforeEach
    void captureTheMailIndicatorsWarning() {
        mailLog = LogCapture.attach(MailHealthIndicator.class);
        contract = ApiContract.servedBy(api);
    }

    /** The mail indicator warns on every read, so each captured event must be that line and nothing else. */
    @AfterEach
    void onlyTheMailWarningWasLogged() {
        try {
            assertThat(mailLog.appender().list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Mail health check failed");
            });
        } finally {
            mailLog.close();
        }
    }

    @Test
    void aDownTargetAnswers503WithTheReportTheDocumentDescribes() {
        ApiContract.Exchange answer = contract.fetch(HEALTH);

        assertThat(answer.status()).isEqualTo(503);
        contract.assertMatches(HEALTH, answer);
        assertThat(answer.json().path("status").asString()).isEqualTo("DOWN");
        assertThat(answer.json().at("/components/mail/status").asString()).isEqualTo("DOWN");
        assertThat(answer.json().at("/components/mail/connection").toString())
                .isEqualTo("{\"host\":\"127.0.0.1\",\"port\":1}");
        assertThat(mailLog.appender().list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("Mail health check failed");
    }

    @Test
    void eachDataSourceIsNamedByItsBean() {
        JsonNode databases = contract.fetch(HEALTH).json().at("/components/db/components");

        JsonNode primary = databases.at("/dataSource/connection");
        assertThat(primary.path("dataSource").asString()).isEqualTo("dataSource");
        assertThat(primary.path("product").asString()).isEqualTo("H2");
        assertThat(primary.path("hosts")).isEmpty();
        assertThat(primary.path("database").asString()).isNotBlank();
        JsonNode inventory = databases.at("/inventoryDataSource/connection");
        assertThat(inventory.path("product").asString()).isEqualTo("H2");
        assertThat(inventory.path("database").asString()).startsWith("inventory-");
    }

    @Test
    void aFailureMessageIsMaskedAndCountedInsideTheWindow() {
        traces.getAndAwaitComplete(DigestFixtureController.PATH + "?tag=" + SECRET_URL);

        ApiContract.Exchange answer = await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                .pollInterval(PeekabootApi.POLL_INTERVAL)
                .until(
                        () -> contract.fetch(HEALTH),
                        exchange -> !failureOn(exchange).isMissingNode());

        JsonNode failure = failureOn(answer);
        assertThat(failure.path("lastMessage").asString())
                .isEqualTo("digest fixture redis://******@cache.internal:6379");
        assertThat(answer.body()).doesNotContain("s3cret");
        assertThat(answer.json().at("/traces/errors").asInt()).isEqualTo(1);
        // the test profile's level 0: 250ms x 90
        assertThat(answer.json().path("windowMs").asLong()).isEqualTo(22_500L);
        contract.assertMatches(HEALTH, answer);
    }

    @Test
    void a503IsNeitherStoredNorSniffed() {
        HttpHeaders headers = api.restClient().get().uri(HEALTH).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            return HttpHeaders.copyOf(response.getHeaders());
        });

        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** Ordered behind an application request whose timing has landed, as in {@link SelfTrafficIT}. */
    @Test
    void theMonitorsRequestsAreNeitherTracedNorTimed() {
        for (int i = 0; i < 3; i++) {
            api.restClient().get().uri(HEALTH).exchange((request, response) -> {
                assertThat(response.getStatusCode().value()).isEqualTo(503);
                assertThat(response.getHeaders().containsHeader("Server-Timing"))
                        .isFalse();
                return null;
            });
        }
        long before = timedRequests("/persons");
        api.get("/persons");
        await().atMost(PeekabootApi.ASYNC_TIMEOUT).until(() -> timedRequests("/persons") > before);

        assertThat(meterRegistry.find("http.server.requests").timers())
                .extracting(timer -> timer.getId().getTag("uri"))
                .doesNotContain(HEALTH);
    }

    private static JsonNode failureOn(ApiContract.Exchange exchange) {
        return exchange.json()
                .path("failures")
                .valueStream()
                .filter(failure -> DigestFixtureController.PATH.equals(
                        failure.path("route").asString()))
                .findFirst()
                .orElse(MissingNode.getInstance());
    }

    private long timedRequests(String uri) {
        return meterRegistry.find("http.server.requests").tag("uri", uri).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }
}
