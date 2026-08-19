package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardShellTest extends PlaywrightTestBase {

    @Test
    void dashboardRendersHeaderAndDefaultTab() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        assertThat(page.isVisible("#dashboard-tab")).isTrue();
    }

    @Test
    void toolbarIsInjectedIntoApplicationPages() {
        openPersonsPage();

        assertThat(page.isVisible("#peekaboot-toolbar-host")).isTrue();
    }
}
