package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.RequestEchoController;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * A local run refuses a request that a forwarding proxy relays for another machine, whichever way the
 * host trusts the proxy's headers. A subclass names the strategy; the cases are the same. Only
 * {@code X-Forwarded-For} is sent: on Boot 4.2 the framework filter hides a {@code Forwarded} header
 * without reading it, so that header would behave differently on each line.
 */
@SpringBootTest(
        classes = {TestingApp.class, LocalRunRefusalIT.LocalRun.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class ForwardedHeadersContract {

    private static final String TRACES = "/peekaboot/api/traces";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final String ANOTHER_MACHINE = "203.0.113.9";

    @LocalServerPort
    private int port;

    /** Boot 4.2 renamed the property; the host sets exactly one of the two names. */
    static String strategyProperty() {
        return BootLine.isAtLeast(4, 2)
                ? "server.request.header.forwarded.strategy"
                : "server.forward-headers-strategy";
    }

    @Test
    void aRequestRelayedForAnotherMachineIsRefused() {
        assertForwardingTakesEffect();

        assertThat(relayedFor(ANOTHER_MACHINE).statusOf(TRACES)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRelayOfThisMachinesOwnRequestIsAdmitted() {
        assertForwardingTakesEffect();

        assertThat(relayedFor("127.0.0.1").statusOf(TRACES)).isEqualTo(HttpStatus.OK);
    }

    private void assertForwardingTakesEffect() {
        assertThat(relayedFor(ANOTHER_MACHINE).get(RequestEchoController.REMOTE_ADDRESS_PATH))
                .as("the client address the application sees")
                .isEqualTo(ANOTHER_MACHINE);
    }

    private PeekabootApi relayedFor(String client) {
        return new PeekabootApi(port).withHeader(FORWARDED_FOR, client);
    }
}
