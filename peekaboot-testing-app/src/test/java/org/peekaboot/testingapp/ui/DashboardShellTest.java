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
}
