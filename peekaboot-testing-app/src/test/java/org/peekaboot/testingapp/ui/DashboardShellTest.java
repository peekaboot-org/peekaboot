package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardShellTest extends PlaywrightTestBase {

    @Test
    void dashboardRendersHeaderAndDefaultTab() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        // openDashboard() guarantees data has arrived, so this proves a real render
        // (the artifact name from build-info.properties), not just an empty container.
        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    @Test
    void toolbarIsInjectedIntoApplicationPages() {
        openPersonsPage();

        // isVisible() on the host element only proves the injected <div> exists; the
        // toolbar itself lives inside its shadow root, so require that to be attached.
        boolean shadowRootAttached = (boolean) page.evaluate(
                "document.getElementById('peekaboot-toolbar-host').shadowRoot !== null");
        assertThat(shadowRootAttached).isTrue();
    }

    /**
     * main.js imports {open, close} directly from trace-detail.js (no more
     * window.PeekabootTraceDetail global - see trace-detail.js's header comment). Its
     * hash-routing handles a deep link to a specific trace (`#traces/<id>`) by calling that
     * imported open() itself, before the traces tab that lists them exists (Task 15) - so a
     * direct navigation to such a link is enough to prove the import actually loaded and
     * ran, independent of whether the trace id resolves to anything real.
     */
    @Test
    void dashboardLoadsTheTraceDetailOverlayModule() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/deadbeef");

        page.waitForSelector("#peekaboot-trace-overlay");
    }
}
