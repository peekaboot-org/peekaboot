package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * With {@code peekaboot.health.fail-on-problems} switched on, an ERROR problem makes the report
 * answer 503 although the application's own health is UP. A context of its own: the five 500s
 * must not leak into the report of a class that expects no problems.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.health.fail-on-problems=true")
class HealthFailOnProblemsIT extends TestingAppITBase {

    private static final String HEALTH = "/peekaboot/api/health";

    @Test
    void optedInAServerErrorProblemAnswers503WhileHealthIsUp() {
        ApiContract contract = ApiContract.servedBy(api);
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            for (int i = 0; i < 5; i++) {
                api.statusOf(OutcomeFixtureController.statusPath(500));
            }
            ticks.nextMatching(tick ->
                    tick.path("values").path("http-throughput.server-errors").asDouble() > 0);
        }

        ApiContract.Exchange answer = await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                .pollInterval(PeekabootApi.POLL_INTERVAL)
                .until(() -> contract.fetch(HEALTH), exchange -> exchange.status() == 503);

        assertThat(answer.json().path("status").asString()).isEqualTo("UP");
        assertThat(answer.json()
                        .path("problems")
                        .valueStream()
                        .map(p -> p.path("rule").asString()))
                .contains("SERVER_ERRORS");
        contract.assertMatches(HEALTH, answer);
    }
}
