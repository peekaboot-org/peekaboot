package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.MachineInfo;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The machine facts survive the trip to JSON. The server runs in this JVM, so the endpoint has
 * to serialise exactly the cached {@link MachineInfo} this test reads directly - no hardcoded
 * network or CPU facts on either side. What the Overview tab does with them is
 * {@code OverviewMachineIT}'s question.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MachineInsightsIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void insightsApiCarriesCpuTopologyAndNetworkAddresses() {
        JsonNode machine = api.getJson("/peekaboot/api/actuator/all/insights")
                .path("runtime")
                .path("machine");
        MachineInfo current = MachineInfo.current();

        if (current.cpuTopology() != null) {
            assertThat(machine.path("cpuTopology").path("physicalCores").asInt())
                    .isEqualTo(current.cpuTopology().physicalCores());
            assertThat(machine.path("cpuTopology").path("threadsPerCore").asInt())
                    .isEqualTo(current.cpuTopology().threadsPerCore());
        }
        assertThat(machine.path("networkAddresses").isArray()).isTrue();
        assertThat(machine.path("networkAddresses").size())
                .isEqualTo(current.networkAddresses().size());
    }
}
