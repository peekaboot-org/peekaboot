package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        // the IPv4/IPv6 tab labels carry the context - the rows themselves are bare
        assertThat(text).doesNotContain("IP Address");
        assertThat(page.locator("#machine-info .pk-machine-net__addr").count()).isEqualTo(addresses.size());
    }

    @Test
    void networkAddressesSplitIntoFamilyTabsDefaultingToIpv4() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        if (addresses.isEmpty()) {
            // no addresses at all - the whole section stays absent, tabs included
            assertThat(page.locator("#machine-net-tabs").count()).isZero();
            return;
        }

        List<NetworkAddress> v4 = family(addresses, false);
        List<NetworkAddress> v6 = family(addresses, true);
        String defaultFamily = v4.isEmpty() ? "ipv6" : "ipv4";

        assertThat(page.getAttribute("#machine-net-" + defaultFamily + "-btn", "aria-selected"))
                .isEqualTo("true");
        assertThat(page.isVisible("#machine-net-" + defaultFamily)).isTrue();

        // each row sits in its family's panel; a family with no addresses hides its
        // tab, the same way the main strip hides tabs for absent features
        assertThat(page.locator("#machine-net-ipv4 .pk-machine-net__addr").count())
                .isEqualTo(v4.size());
        assertThat(page.locator("#machine-net-ipv6 .pk-machine-net__addr").count())
                .isEqualTo(v6.size());
        if (v4.isEmpty()) {
            assertThat(page.isVisible("#machine-net-ipv4-btn")).isFalse();
        }
        if (v6.isEmpty()) {
            assertThat(page.isVisible("#machine-net-ipv6-btn")).isFalse();
        }
    }

    @Test
    void switchingToTheIpv6TabShowsTheV6SetAndSurvivesARefresh() {
        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        assumeTrue(
                !family(addresses, false).isEmpty() && !family(addresses, true).isEmpty(),
                "needs a machine with both an IPv4 and an IPv6 address");

        openDashboard();
        page.waitForSelector("#machine-net-ipv6-btn");
        page.click("#machine-net-ipv6-btn");

        assertThat(page.getAttribute("#machine-net-ipv6-btn", "aria-selected")).isEqualTo("true");
        assertThat(page.isVisible("#machine-net-ipv6")).isTrue();
        assertThat(page.isHidden("#machine-net-ipv4")).isTrue();
        String text = page.textContent("#machine-net-ipv6");
        for (NetworkAddress address : family(addresses, true)) {
            assertThat(text).contains(address.address());
        }

        // the open family is transient per-browser UI, but a refresh rebuild must keep
        // it (the group-expansion convention) - tag the panel so the rebuild is provable
        page.evaluate("() => document.querySelector('#machine-net-ipv6').dataset.probe = 'pre-refresh'");
        page.click("#refresh-btn");
        page.waitForFunction("() => !document.querySelector('#machine-net-ipv6').dataset.probe");
        assertThat(page.getAttribute("#machine-net-ipv6-btn", "aria-selected")).isEqualTo("true");
        assertThat(page.isVisible("#machine-net-ipv6")).isTrue();
    }

    @Test
    void networkTabsExposeAsATablistAndAreKeyboardOperable() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        assumeTrue(!addresses.isEmpty(), "needs a machine with at least one non-local address");

        assertThat(page.locator("#machine-net-tabs").ariaSnapshot()).contains("tablist");
        String defaultFamily = family(addresses, false).isEmpty() ? "ipv6" : "ipv4";
        String defaultButton = "#machine-net-" + defaultFamily + "-btn";
        assertThat(page.getAttribute(defaultButton, "role")).isEqualTo("tab");
        assertThat(page.getAttribute(defaultButton, "aria-controls")).isEqualTo("machine-net-" + defaultFamily);
        assertThat(page.getAttribute("#machine-net-" + defaultFamily, "role")).isEqualTo("tabpanel");
        assertThat(page.getAttribute("#machine-net-" + defaultFamily, "aria-labelledby"))
                .isEqualTo("machine-net-" + defaultFamily + "-btn");

        // arrow keys move selection over the visible families (wrapping onto the only
        // one where a family is absent) and keep the roving tabindex on the selection
        boolean bothFamilies =
                !family(addresses, false).isEmpty() && !family(addresses, true).isEmpty();
        String nextFamily = bothFamilies ? (defaultFamily.equals("ipv4") ? "ipv6" : "ipv4") : defaultFamily;
        page.focus(defaultButton);
        page.keyboard().press("ArrowRight");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(nextFamily);
        assertThat(page.getAttribute("#machine-net-" + nextFamily + "-btn", "aria-selected"))
                .isEqualTo("true");
        assertThat(page.isVisible("#machine-net-" + nextFamily)).isTrue();
        assertThat(page.evaluate("() => document.querySelector('#machine-net-" + nextFamily + "-btn').tabIndex"))
                .isEqualTo(0);
    }

    /** Splits by address family the way the card does: a ":" in the literal means IPv6. */
    private static List<NetworkAddress> family(List<NetworkAddress> addresses, boolean ipv6) {
        return addresses.stream()
                .filter(address -> address.address().contains(":") == ipv6)
                .toList();
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
