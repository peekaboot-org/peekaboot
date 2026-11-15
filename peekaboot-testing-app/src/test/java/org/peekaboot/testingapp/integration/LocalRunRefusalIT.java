package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.security.DashboardAccess;
import org.peekaboot.backend.security.LoopbackOnlyAccess;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * A local run's refusal is the 403 the document describes, over real HTTP. A test launch never
 * reads as a local run, so this context names the rule a local run without the guard gets.
 * {@code LoopbackOnlyIT} in the autoconfigure module covers the detection and the rule's cases.
 */
@SpringBootTest(
        classes = {TestingApp.class, LocalRunRefusalIT.LocalRun.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LocalRunRefusalIT {

    private static final String TRACES = "/peekaboot/api/traces";

    @LocalServerPort
    private int port;

    @Test
    void theRefusalIsTheForbiddenTheDocumentDescribes() {
        PeekabootApi thisMachine = new PeekabootApi(port);
        ApiContract contract = ApiContract.servedBy(thisMachine);

        ApiContract.Exchange refusal =
                ApiContract.exchange(thisMachine.withHeader("Sec-Fetch-Site", "cross-site"), TRACES);

        assertThat(refusal.status()).isEqualTo(403);
        contract.assertMatches(TRACES, refusal);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalRun {
        @Bean
        DashboardAccess loopbackOnlyAccess() {
            return new LoopbackOnlyAccess();
        }
    }
}
