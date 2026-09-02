package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.ContainerRuntime;
import org.peekaboot.backend.domain.runtime.CpuTopology;
import org.peekaboot.backend.domain.runtime.MachineInfo;
import org.peekaboot.backend.domain.runtime.NetworkAddress;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OverviewMachineIT extends PlaywrightTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void machineCardShowsCpuMemoryAndContainerFacts() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        String text = page.textContent("#machine-info");
        assertThat(text).contains("CPU Cores");
        assertThat(text).contains(String.valueOf(Runtime.getRuntime().availableProcessors()));
        assertThat(text).contains("Total Memory");
        assertThat(text).contains("Max Heap");
    }

    @Test
    void containerRowSaysWhatTheSharedDetectorReports() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        // the build itself may run inside a container, so assert against the same
        // detector the backend serialises instead of a literal "none"
        String value = page.textContent("#machine-info .pk-kv:has(.pk-kv__key:text-is('Container')) .pk-kv__value");
        assertThat(value).isEqualTo(ContainerRuntime.current().wireName());
    }

    @Test
    void insightsApiCarriesCpuTopologyAndNetworkAddresses() {
        // the server runs in this JVM, so the API must serialise exactly the cached
        // MachineInfo this test reads directly - no hardcoded network or CPU facts
        APIResponse response = page.request().get(baseUrl + "/peekaboot/api/actuator/all/insights");
        assertThat(response.status()).isEqualTo(200);
        JsonNode machine = JSON.readTree(response.text()).path("runtime").path("machine");
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

    @Test
    void machineCardRendersEachDiscoveredIpWithItsHostname() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        String text = page.textContent("#machine-info");
        for (NetworkAddress address : addresses) {
            assertThat(text).contains(address.address());
            if (address.hostname() != null) {
                assertThat(text).contains(address.hostname());
            }
        }
        assertThat(page.locator("#machine-info .pk-kv:has(.pk-kv__key:text-is('IP Address'))")
                        .count())
                .isEqualTo(addresses.size());
    }

    @Test
    void cpuCoresRowCarriesThePhysicalTopologyWhereKnown() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        String value = page.textContent("#machine-info .pk-kv:has(.pk-kv__key:text-is('CPU Cores')) .pk-kv__value");
        int logical = Runtime.getRuntime().availableProcessors();
        CpuTopology topology = MachineInfo.current().cpuTopology();
        if (topology == null) {
            assertThat(value).isEqualTo(String.valueOf(logical));
        } else if (topology.threadsPerCore() > 1) {
            assertThat(value)
                    .isEqualTo(logical + " (" + topology.physicalCores() + " cores × " + topology.threadsPerCore()
                            + " threads)");
        } else {
            assertThat(value).isEqualTo(logical + " (" + topology.physicalCores() + " cores, SMT off)");
        }
    }

    @Test
    void cpuCoresMovedOffTheJvmDefaultsCard() {
        openDashboard();
        page.waitForSelector("#jvm-defaults-info .pk-kv");

        // the machine card owns the processor count; the JVM Defaults card must not
        // render a second copy
        assertThat(page.textContent("#jvm-defaults-info")).doesNotContain("CPU Cores");
    }

    @Test
    void datasourceCardSitsBesideJvmDefaultsInTheCardGrid() {
        openDashboard();
        page.waitForSelector(".pk-grid > .pk-card[data-datasource]");

        // the two-column card grid pairs grid neighbours, so the first datasource card
        // has to follow JVM Defaults in DOM order to land beside it
        Object neighbour = page.evaluate(
                "() => document.querySelector('#jvm-defaults-card').nextElementSibling?.dataset.datasource ?? null");
        assertThat(neighbour)
                .as("the first datasource card directly follows the JVM Defaults card")
                .isNotNull();
    }
}
