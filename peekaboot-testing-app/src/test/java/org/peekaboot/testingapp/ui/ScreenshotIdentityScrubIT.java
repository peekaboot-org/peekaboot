package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.MachineInfo;
import org.peekaboot.backend.domain.runtime.NetworkAddress;
import org.peekaboot.backend.domain.runtime.ProcessInfo;

/**
 * The guard behind {@link ScreenshotIdentityScrub}: it runs against the real Overview DOM,
 * so a renamed selector or a restructured row fails the build instead of quietly publishing
 * the capture machine's identity in the next screenshot.
 */
class ScreenshotIdentityScrubIT extends PlaywrightTestBase {

    @Test
    void userAndPidStopNamingTheCaptureMachine() {
        openDashboard();
        page.waitForSelector("#os-info .pk-kv");
        ProcessInfo process = ProcessInfo.current();
        assertThat(rowValue("#os-info", "PID")).isEqualTo(String.valueOf(process.pid()));

        ScreenshotIdentityScrub.applyTo(page);

        // the row values rather than a substring search of the card: a real pid or
        // username can legitimately occur inside a placeholder - a pid of 1000 sits
        // in the replacement uid
        assertThat(rowValue("#os-info", "User")).isEqualTo(ScreenshotIdentityScrub.USER);
        assertThat(rowValue("#os-info", "PID")).isEqualTo(ScreenshotIdentityScrub.PID);
    }

    @Test
    void theParentProcessChainIsReplacedWholesale() {
        assumeTrue(!ProcessInfo.current().parentProcesses().isEmpty(), "needs a JVM with a parent process");
        openDashboard();
        page.waitForSelector("#os-info .pk-kv");

        ScreenshotIdentityScrub.applyTo(page);

        // the whole row, not a per-pid check: a real pid is a bare number, so any digit
        // of it can legitimately appear inside the placeholder
        assertThat(rowValue("#os-info", "Process Tree")).isEqualTo(ScreenshotIdentityScrub.PROCESS_TREE);
    }

    @Test
    void everyNetworkAddressBecomesADocumentationAddress() {
        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        assumeTrue(!addresses.isEmpty(), "needs a machine with at least one non-local address");
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        ScreenshotIdentityScrub.applyTo(page);

        String text = page.textContent("#machine-info");
        for (NetworkAddress address : addresses) {
            assertThat(text).doesNotContain(address.address());
            if (address.hostname() != null) {
                assertThat(text).doesNotContain(address.hostname());
            }
        }
        assertThat(page.locator("#machine-info .pk-machine-net__addr").count()).isEqualTo(addresses.size());
    }

    @Test
    void aScrubbedAddressStaysInItsOwnFamilyPanel() {
        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        assumeTrue(!addresses.isEmpty(), "needs a machine with at least one non-local address");
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        ScreenshotIdentityScrub.applyTo(page);

        // the panels are built from the real addresses, so a placeholder of the wrong
        // family would photograph an IPv6 literal under the IPv4 tab
        for (String row :
                page.locator("#machine-net-ipv4 .pk-machine-net__addr").allTextContents()) {
            assertThat(row).startsWith(ScreenshotIdentityScrub.IPV4_PREFIX);
        }
        for (String row :
                page.locator("#machine-net-ipv6 .pk-machine-net__addr").allTextContents()) {
            assertThat(row).startsWith(ScreenshotIdentityScrub.IPV6_PREFIX);
        }
    }

    @Test
    void anAddressKeepsWhetherItHadAHostnameToShow() {
        List<NetworkAddress> addresses = MachineInfo.current().networkAddresses();
        assumeTrue(
                addresses.stream().anyMatch(address -> address.hostname() != null),
                "needs a machine with at least one reverse-resolving address");
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");
        long named =
                addresses.stream().filter(address -> address.hostname() != null).count();

        ScreenshotIdentityScrub.applyTo(page);

        long placeholdersWithHostname = page.locator("#machine-info .pk-machine-net__addr").allTextContents().stream()
                .filter(row -> row.endsWith(" (" + ScreenshotIdentityScrub.HOSTNAME + ")"))
                .count();
        assertThat(placeholdersWithHostname).isEqualTo(named);
    }

    @Test
    void factsThatDoNotNameTheMachineSurviveUntouched() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");
        String architecture = rowValue("#os-info", "Architecture");
        String cores = rowValue("#machine-info", "CPU Cores");
        String container = rowValue("#machine-info", "Container");

        ScreenshotIdentityScrub.applyTo(page);

        assertThat(rowValue("#os-info", "Architecture")).isEqualTo(architecture);
        assertThat(rowValue("#machine-info", "CPU Cores")).isEqualTo(cores);
        assertThat(rowValue("#machine-info", "Container")).isEqualTo(container);
    }

    @Test
    void aRenamedOrMissingIdentityRowFailsInsteadOfPublishingTheRealOne() {
        openDashboard();
        page.waitForSelector("#os-info .pk-kv");
        // the scrub is only as good as its selectors; drift has to be loud, because the
        // silent outcome is a published screenshot naming whoever ran the capture
        page.evaluate("() => document.querySelector('#os-info').innerHTML = ''");

        assertThatThrownBy(() -> ScreenshotIdentityScrub.applyTo(page)).hasMessageContaining("User");
    }

    /** The value cell of the row a card renders under {@code key}. */
    private String rowValue(String card, String key) {
        return page.textContent(card + " .pk-kv:has(.pk-kv__key:text-is('" + key + "')) .pk-kv__value");
    }
}
