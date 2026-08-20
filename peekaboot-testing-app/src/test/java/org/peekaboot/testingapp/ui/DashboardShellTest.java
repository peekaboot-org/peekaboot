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
     * trace-detail.js gained a top-level `export`, which only parses as an ES module, so
     * its <script> tag in index.html had to move from a classic `defer` script to
     * type="module". peekaboot.js swallows a missing overlay silently
     * (`if (!window.PeekabootTraceDetail) return`), so a broken/reverted script tag would
     * leave the dashboard's trace-detail feature silently dead with every other test still
     * green - this proves the module actually loaded and ran.
     */
    @Test
    void dashboardLoadsTheTraceDetailOverlayModule() {
        openDashboard();

        assertThat(page.evaluate("() => typeof window.PeekabootTraceDetail")).isEqualTo("object");
        assertThat(page.evaluate("() => typeof window.PeekabootTraceDetail.open")).isEqualTo("function");
    }
}
