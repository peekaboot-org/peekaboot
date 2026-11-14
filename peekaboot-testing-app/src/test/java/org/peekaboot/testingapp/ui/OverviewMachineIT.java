package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.ContainerRuntime;
import org.peekaboot.backend.domain.runtime.CpuTopology;
import org.peekaboot.backend.domain.runtime.MachineInfo;
import org.peekaboot.backend.domain.runtime.NetworkAddress;
import tools.jackson.databind.node.ObjectNode;

class OverviewMachineIT extends PlaywrightTestBase {

    private static final String DATASOURCE_TOGGLE = ".pk-card[data-datasource] .pk-datasources__toggle button";
    private static final String DATASOURCE_PARAMS = ".pk-card[data-datasource] .pk-datasources__params";

    /** What the card renders a byte count as; the same module the card builds its rows with. */
    private String formatBytes(long bytes) {
        // As a string: Playwright's argument serializer takes no Java long.
        return (String) page.evaluate(
                "async (value) => (await import('/peekaboot/ui/shared/format.js')).formatBytes(Number(value))",
                String.valueOf(bytes));
    }

    /** Serves every insights answer with its {@code runtime} node changed by {@code rewrite}. */
    private void serveRuntimeRewrittenBy(Consumer<ObjectNode> rewrite) {
        serveInsightsRewrittenBy(insights -> rewrite.accept((ObjectNode) insights.path("runtime")));
    }

    /** Serves every insights answer changed by {@code rewrite}. */
    private void serveInsightsRewrittenBy(Consumer<ObjectNode> rewrite) {
        page.route("**/peekaboot/api/actuator/all/insights**", route -> {
            APIResponse response = route.fetch();
            ObjectNode insights = (ObjectNode) readJson(response.text());
            rewrite.accept(insights);
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(insights.toString()));
        });
    }

    /** Splits by address family the way the card does: a ":" in the literal means IPv6. */
    private static List<NetworkAddress> family(List<NetworkAddress> addresses, boolean ipv6) {
        return addresses.stream()
                .filter(address -> address.address().contains(":") == ipv6)
                .toList();
    }

    /**
     * The Total Memory row against the machine this JVM runs on, formatted by the shared
     * formatter the row itself uses - a card of labels over placeholder numbers passes a
     * presence check and fails this.
     */
    @Test
    void machineCardShowsCpuMemoryAndContainerFacts() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        MachineInfo current = MachineInfo.current();
        assertThat(page.textContent("#machine-info")).contains("CPU Cores");
        assertThat(dashboard.kvValue("#machine-info", "Total Memory")).isEqualTo(formatBytes(current.totalMemory()));
    }

    /**
     * Max Heap is the memory reading's heapMax, the figure the heap meter divides by. The served
     * value is replaced so that no other heap reading can match it by chance.
     */
    @Test
    void maxHeapRowShowsTheHeapMetersMaximum() {
        long servedHeapMax = 123_456_789L;
        serveRuntimeRewrittenBy(runtime -> ((ObjectNode) runtime.path("memory")).put("heapMax", servedHeapMax));
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        assertThat(dashboard.kvValue("#machine-info", "Max Heap")).isEqualTo(formatBytes(servedHeapMax));
    }

    /**
     * Max Heap end to end: the info endpoint's process memory, mapped into the insights
     * response this page read, then rendered on the row. The app runs in this JVM.
     */
    @Test
    void maxHeapRowShowsTheServedHeapMaximumOfThisJvm() {
        Response insights = page.waitForResponse(
                response -> response.url().contains("/peekaboot/api/actuator/all/insights"), this::openDashboard);
        long servedHeapMax = readJson(insights.text())
                .path("runtime")
                .path("memory")
                .path("heapMax")
                .asLong();
        page.waitForSelector("#machine-info .pk-kv");
        long jvmHeapMax =
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();

        assertThat(servedHeapMax).isEqualTo(jvmHeapMax);
        assertThat(dashboard.kvValue("#machine-info", "Max Heap")).isEqualTo(formatBytes(servedHeapMax));
    }

    @Test
    void containerRowSaysWhatTheSharedDetectorReports() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        // the build itself may run inside a container, so assert against the same
        // detector the backend serialises instead of a literal "none"
        String value = dashboard.kvValue("#machine-info", "Container");
        assertThat(value).isEqualTo(ContainerRuntime.current().wireName());
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
        AtomicInteger answers = new AtomicInteger();
        serveRuntimeRewrittenBy(runtime ->
                ((ObjectNode) runtime.path("machine")).put("cpuModel", "CPU of answer " + answers.incrementAndGet()));

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

        // the open family is transient per-browser UI, but a refresh that rebuilds the card
        // must keep it. Every answer names another CPU model, so the card is rebuilt, and the
        // tag on the panel proves it
        page.evaluate("() => document.querySelector('#machine-net-ipv6').dataset.probe = 'pre-refresh'");
        page.click("#refresh-btn");
        page.waitForFunction("() => !document.querySelector('#machine-net-ipv6').dataset.probe");
        assertThat(page.getAttribute("#machine-net-ipv6-btn", "aria-selected")).isEqualTo("true");
        assertThat(page.isVisible("#machine-net-ipv6")).isTrue();
    }

    /**
     * A card whose data did not change is left alone by a refresh, so focus inside it stays
     * where the reader put it. The probe is on the focused tab: a rebuilt card carries none.
     */
    @Test
    void aFocusedNetworkTabKeepsFocusAcrossARefresh() {
        assumeTrue(
                !MachineInfo.current().networkAddresses().isEmpty(),
                "needs a machine with at least one non-local address");
        openDashboard();
        page.focus("#machine-net-tabs .pk-tab[aria-selected='true']");
        page.evaluate("() => document.activeElement.dataset.probe = '1'");

        refreshLeavingFocusInPlace("**/api/actuator/all/insights*");

        assertThat(page.evaluate("() => document.activeElement.dataset.probe"))
                .as("the focused element is still the network tab")
                .isEqualTo("1");
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

    @Test
    void cpuCoresRowCarriesThePhysicalTopologyWhereKnown() {
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");

        String value = dashboard.kvValue("#machine-info", "CPU Cores");
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
    void jvmDefaultsCardDoesNotRepeatTheCpuCoreCount() {
        openDashboard();
        page.waitForSelector("#jvm-defaults-info .pk-kv");

        // the machine card owns the processor count; the JVM Defaults card must not
        // render a second copy
        assertThat(page.textContent("#jvm-defaults-info")).doesNotContain("CPU Cores");
    }

    @Test
    void datasourceCardSitsBesideJvmDefaultsInTheCardGrid() {
        openDashboard();
        page.waitForSelector(".pk-card[data-datasource]");

        // the two-column card grid pairs grid neighbours, so the first datasource card
        // has to be the grid's next item after JVM Defaults to land beside it
        assertThat(page.locator("#jvm-defaults-card + #datasource-cards > .pk-card[data-datasource]:first-child")
                        .count())
                .as("the first datasource card directly follows the JVM Defaults card")
                .isEqualTo(1);
        assertThat(page.evaluate("() => getComputedStyle(document.getElementById('datasource-cards')).display"))
                .as("the cards' own parent takes no box, so each card is an item of the grid")
                .isEqualTo("contents");
    }

    /**
     * The datasource card keeps its connection parameters behind a toggle: a JDBC URL's query
     * string carries whatever the deployment put there, so it is not on screen by default.
     * The label never changes: aria-expanded alone says which way the toggle stands, and
     * aria-controls names the params it opens.
     */
    @Test
    void theDatasourceCardHidesItsConnectionParamsBehindAToggle() {
        openDashboard();
        page.waitForSelector(DATASOURCE_TOGGLE);

        Locator toggle = page.locator(DATASOURCE_TOGGLE).first();
        Locator params = page.locator(DATASOURCE_PARAMS).first();
        assertThat(toggle.getAttribute("aria-controls")).isNotBlank().isEqualTo(params.getAttribute("id"));
        assertConnectionParams(toggle, params, false);

        toggle.click();

        assertConnectionParams(toggle, params, true);
        assertThat(params.textContent()).isNotBlank();

        toggle.click();

        assertConnectionParams(toggle, params, false);
    }

    private static void assertConnectionParams(Locator toggle, Locator params, boolean open) {
        assertThat(toggle.textContent()).isEqualTo("Connection Params");
        assertThat(toggle.getAttribute("aria-expanded")).isEqualTo(String.valueOf(open));
        assertThat(params.isVisible()).isEqualTo(open);
    }

    /**
     * The testing app has two datasources, so a params id that repeats would point the
     * second card's toggle at the first card's params.
     */
    @Test
    void everyConnectionParamsToggleNamesTheParamsOfItsOwnCard() {
        openDashboard();
        page.waitForSelector(DATASOURCE_TOGGLE);

        @SuppressWarnings("unchecked")
        List<Boolean> namesItsOwnParams = (List<Boolean>) page.evalOnSelectorAll(DATASOURCE_TOGGLE, """
                toggles => toggles.map(toggle => document.getElementById(toggle.getAttribute('aria-controls'))
                        === toggle.closest('.pk-card').querySelector('.pk-datasources__params'))
                """);

        assertThat(namesItsOwnParams).hasSizeGreaterThan(1).containsOnly(true);
    }

    /**
     * A refresh leaves the card of a datasource that did not change alone, so the connection
     * params the reader opened are still on screen after it.
     */
    @Test
    void openConnectionParamsStayOpenAcrossARefresh() {
        openDashboard();
        page.waitForSelector(DATASOURCE_TOGGLE);
        page.locator(DATASOURCE_TOGGLE).first().click();

        refreshLeavingFocusInPlace("**/api/actuator/all/insights*");

        assertThat(page.locator(DATASOURCE_PARAMS).first().isVisible())
                .as("the connection params the reader opened")
                .isTrue();
    }

    /**
     * Sizes and the heap percentage are decimal figures. Served with values that have decimals,
     * the Machine card, the Memory card and a health component's details write them with the
     * decimal mark of the locale the reader chose.
     */
    @Test
    void sizesAndTheHeapPercentageAreWrittenInTheChosenLocale() {
        long kib = 1024L;
        long mib = kib * kib;
        long gib = mib * kib;
        serveInsightsRewrittenBy(insights -> {
            ObjectNode runtime = (ObjectNode) insights.path("runtime");
            ((ObjectNode) runtime.path("machine")).put("totalMemory", 1536 * gib);
            ((ObjectNode) runtime.path("memory"))
                    .put("heapUsed", 1536 * mib)
                    .put("heapMax", 12 * gib + 512 * mib)
                    .put("heapUsedPercent", 37.5)
                    .put("nonHeapUsed", 1536 * kib);
            runtime.putArray("storage")
                    .addObject()
                    .put("path", "/data")
                    .put("total", 3 * gib)
                    .put("free", 1536 * mib)
                    .put("usedPercent", 50.0);
            ObjectNode diskSpace = ((ObjectNode) insights.path("health"))
                    .putArray("components")
                    .addObject()
                    .put("name", "diskSpace")
                    .put("status", "UP");
            diskSpace
                    .putObject("details")
                    .put("total", 3 * gib)
                    .put("free", 1536 * mib)
                    .put("threshold", 10 * mib);
        });
        openDashboard();
        page.waitForSelector("#machine-info .pk-kv");
        assertThat(dashboard.kvValue("#machine-info", "Total Memory")).isEqualTo("1.50 TB");

        page.selectOption("#locale-select", "de-DE");
        dashboard.awaitKvValue("#machine-info", "Total Memory", "1,50 TB");

        assertThat(List.of(
                        dashboard.kvValue("#machine-info", "Max Heap"),
                        dashboard.kvValue("#memory-info", "Heap"),
                        dashboard.kvValue("#memory-info", "Non-Heap"),
                        dashboard.kvValue("#memory-info", "/data"),
                        page.textContent("#components-grid .pk-component-grid__details")))
                .containsExactly(
                        "12,5 GB",
                        "1,50 GB / 12,5 GB (37,5%)",
                        "1,50 MB",
                        "1,50 GB used / 3,00 GB total (1,50 GB free)",
                        "total: 3,00 GB\nfree: 1,50 GB\nthreshold: 10,0 MB");
    }
}
