package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.RequestThreadObservations;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The dashboard's polling is not the application's traffic: not in its request metrics, which the
 * http-* insights read, and not in its traces, not even the work a dashboard request does.
 */
class SelfTrafficIT extends TestingAppITBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RequestThreadObservations observations;

    /**
     * Ordered behind an application request whose timing has landed: the dashboard request
     * finished first, so its timing would have landed too. Every class in this context polls the
     * dashboard, so a recorded dashboard timing shows up here whatever this test sent.
     */
    @Test
    void theDashboardsRequestsAreNotTimedAsTheApplications() {
        api.get("/peekaboot/api/features");
        long before = timedRequests("/persons");
        api.get("/persons");
        await().atMost(Duration.ofSeconds(15)).until(() -> timedRequests("/persons") > before);

        assertThat(meterRegistry.find("http.server.requests").timers())
                .extracting(timer -> timer.getId().getTag("uri"))
                .noneMatch(uri -> uri != null && uri.startsWith("/peekaboot"));
    }

    /** The control comes first: the health check's work is observed when an application request runs it. */
    @Test
    void workTheDashboardDoesWhileServingARequestStartsNoObservation() {
        api.statusOf("/actuator/health");
        assertThat(observations.startedServing("/actuator/health"))
                .as("the health check's own work is observed on an application request, or this test proves nothing")
                .isNotEmpty();

        api.get("/peekaboot/api/actuator/all/insights");

        assertThat(observations.startedServing("/peekaboot")).isEmpty();
    }

    private long timedRequests(String uri) {
        return meterRegistry.find("http.server.requests").tag("uri", uri).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }
}
