package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.ContainerRuntime;

class OverviewMachineIT extends PlaywrightTestBase {

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
