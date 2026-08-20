package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardTabsTest extends PlaywrightTestBase {

    @Test
    void overviewShowsJavaAndSystemCards() {
        openDashboard();
        page.waitForSelector("#java-info .pk-kv");

        assertThat(page.textContent("#java-info")).contains("Version");
        assertThat(page.textContent("#os-info")).contains("Architecture");
    }

    @Test
    void healthBannerReflectsApplicationHealth() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.textContent("#health-status-text")).isEqualTo("UP");
    }

    @Test
    void memoryMeterIsRenderedWithTheSharedPrimitive() {
        openDashboard();
        page.waitForSelector("#memory-info .pk-meter__fill");

        assertThat(page.isVisible("#memory-info .pk-meter")).isTrue();
    }

    @Test
    void tabStripUsesAriaSelection() {
        openDashboard();

        String selected = (String) page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("dashboard");
    }

    @Test
    void switchingTabsUpdatesTheHashAndSelection() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#environment-tab.active");

        assertThat(page.url()).endsWith("#environment");
        assertThat(page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .isEqualTo("environment");
    }

    @Test
    void deepLinkOpensTheRequestedTab() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#loggers");
        page.waitForSelector("#loggers-tab.active");

        assertThat(page.isVisible("#loggers-tab")).isTrue();
    }

    /**
     * The health banner used to be a click-handled <div> - not reachable by keyboard at
     * all. It is now a real <button> with aria-expanded/aria-controls; this proves Space
     * actually expands it, not just that a mouse click does (which every other test here
     * would still pass even if the element were a div with an onclick handler).
     */
    @Test
    void healthBannerExpandsWithTheKeyboard() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("false");
        assertThat(page.isHidden("#health-components")).isTrue();

        page.focus("#health-banner");
        page.keyboard().press("Space");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("true");
        assertThat(page.isVisible("#health-components")).isTrue();
    }
}
