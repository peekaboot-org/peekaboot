package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.peekaboot.autoconfigure.HealthCheckRuns;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The health report with a check that never answers on its own, a deadline of 2s and
 * Peekaboot's own two log lines. The context is this class's own: the hung check would hold up
 * every other class's report. Without the deadline a request blocks on the latch for good, so the
 * class-level timeout is what turns a regression into a failure instead of a hang.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // long enough that a stalled machine does not time out the checks that do answer
        properties = "peekaboot.health.timeout=2s")
@Import(HealthDeadlineIT.Checks.class)
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HealthDeadlineIT extends TestingAppITBase {

    private static final String HEALTH = "/peekaboot/api/health";

    private static final String DID_NOT_ANSWER = "Peekaboot health: 'hung' did not answer within 2s; reported DOWN";

    private static final String ANSWERED_UP = "Peekaboot health: 'hung' answered UP after ";

    @Autowired
    private HungCheck hung;

    @Autowired
    private ObservedCheck observed;

    @Autowired
    private MeterRegistry meterRegistry;

    private ApiContract contract;

    private LogCapture healthLog;

    @BeforeEach
    void captureTheHealthRunsLog() {
        healthLog = LogCapture.attach(HealthCheckRuns.class);
        contract = ApiContract.servedBy(api);
    }

    /**
     * Lets the hung check finish and waits for its late line, so it lands in the capture and not on
     * the console. The logger is JVM-wide while other contexts run, hence the filter on {@code 'hung'}.
     */
    @AfterEach
    void releaseTheHungCheckAndAssertOnlyPeekabootsLinesWereLogged() {
        try {
            hung.release();
            await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                    .pollInterval(PeekabootApi.POLL_INTERVAL)
                    .until(() -> hungLines(DID_NOT_ANSWER).size()
                            == hungLines("Peekaboot health: 'hung' answered ").size());
            await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                    .pollInterval(PeekabootApi.POLL_INTERVAL)
                    .until(
                            () -> contract.fetch(HEALTH)
                                    .json()
                                    .at("/components/hung/status")
                                    .asString(),
                            "UP"::equals);
            hung.rearm();
            assertThat(healthLog.events())
                    .filteredOn(event -> event.getFormattedMessage().contains("'hung'"))
                    .allSatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).matches(DID_NOT_ANSWER + "|" + ANSWERED_UP + "\\d+ms");
                    });
        } finally {
            healthLog.close();
        }
    }

    @Test
    void aHungCheckIsReportedDownWithinTheDeadline() {
        ApiContract.Exchange answer = contract.fetch(HEALTH);

        assertThat(answer.status()).isEqualTo(503);
        contract.assertMatches(HEALTH, answer);
        assertThat(answer.json().at("/components/hung/status").asString()).isEqualTo("DOWN");
        assertThat(answer.json().at("/components/hung/details/error").asString())
                .isEqualTo("Timed out after 2s");
        assertThat(answer.json().at("/components/hung/durationMs").asLong()).isGreaterThanOrEqualTo(2000);
        assertThat(answer.json().at("/components/ping/status").asString()).isEqualTo("UP");
        assertThat(answer.json().at("/components/observed/status").asString()).isEqualTo("UP");
        assertThat(hungLines(DID_NOT_ANSWER)).hasSize(1);
        assertThat(healthLog.events())
                .filteredOn(event -> event.getFormattedMessage().equals(DID_NOT_ANSWER))
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN);
    }

    @Test
    void aSecondRequestWaitsOnTheCheckInFlight() {
        int before = hung.evaluations();

        ApiContract.Exchange first = contract.fetch(HEALTH);
        ApiContract.Exchange second = contract.fetch(HEALTH);

        assertThat(first.status()).isEqualTo(503);
        assertThat(second.status()).isEqualTo(503);
        // the evaluation runs on its own thread, which a loaded machine may not have started yet
        await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                .pollInterval(PeekabootApi.POLL_INTERVAL)
                .until(() -> hung.evaluations() > before);
        assertThat(hung.evaluations() - before).isEqualTo(1);
        assertThat(hungLines(DID_NOT_ANSWER)).hasSize(1);

        hung.release();
        await().atMost(PeekabootApi.ASYNC_TIMEOUT)
                .pollInterval(PeekabootApi.POLL_INTERVAL)
                .until(() -> !hungLines(ANSWERED_UP).isEmpty());
        ApiContract.Exchange afterwards = contract.fetch(HEALTH);

        assertThat(hung.evaluations() - before).isEqualTo(2);
        assertThat(afterwards.json().at("/components/hung/status").asString()).isEqualTo("UP");
    }

    @Test
    void aHealthChecksObservationsAreNeitherTracedNorTimed() {
        contract.fetch(HEALTH);

        assertThat(observed.evaluations()).isPositive();
        assertThat(meterRegistry.find("peekaboot.it.health-check").timers()).isEmpty();
    }

    private List<String> hungLines(String prefix) {
        return healthLog.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("'hung'") && message.startsWith(prefix))
                .toList();
    }

    /** A check that answers only once released, and DOWN if interrupted. */
    static final class HungCheck implements HealthIndicator {

        private final AtomicInteger evaluations = new AtomicInteger();

        private volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public Health health() {
            evaluations.incrementAndGet();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Health.down().build();
            }
            return Health.up().build();
        }

        int evaluations() {
            return evaluations.get();
        }

        void release() {
            latch.countDown();
        }

        void rearm() {
            latch = new CountDownLatch(1);
        }
    }

    /** A check that runs an observation of its own, as an instrumented client would. */
    static final class ObservedCheck implements HealthIndicator {

        private final AtomicInteger evaluations = new AtomicInteger();

        private final ObservationRegistry registry;

        ObservedCheck(ObservationRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Health health() {
            evaluations.incrementAndGet();
            return Observation.createNotStarted("peekaboot.it.health-check", registry)
                    .observe(() -> Health.up().build());
        }

        int evaluations() {
            return evaluations.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Checks {

        @Bean
        HungCheck hungHealthIndicator() {
            return new HungCheck();
        }

        @Bean
        ObservedCheck observedHealthIndicator(ObservationRegistry registry) {
            return new ObservedCheck(registry);
        }
    }
}
